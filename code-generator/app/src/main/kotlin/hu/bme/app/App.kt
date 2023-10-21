/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package hu.bme.app

import app.model.ResolutionTree
import java.io.File
import java.util.*
import hu.bme.app.Parser
fun main() {

    val prologCode = """

male(john).
male(james).
male(bob).
male(tom).

female(anna).
female(lisa).
female(susan).
female(mary).

parent(john, james).
parent(john, lisa).
parent(anna, james).
parent(anna, lisa).
parent(james, bob).
parent(james, susan).
parent(lisa, mary).
parent(tom, mary).

father(X, Y) :-
    male(X),
    parent(X, Y).

mother(X, Y) :-
    female(X),
    parent(X, Y).


child(X, Y) :-
    parent(Y, X).


sibling(X, Y) :-
    parent(Z, X),
    parent(Z, Y),
    X \= Y.

brother(X, Y) :-
    male(X),
    sibling(X, Y).

sister(X, Y) :-
    female(X),
    sibling(X, Y).

grandparent(X, Y) :-
    parent(X, Z),
    parent(Z, Y).

grandfather(X, Y) :-
    male(X),
    grandparent(X, Y).

grandmother(X, Y) :-
    female(X),
    grandparent(X, Y).

    """.trimIndent()

    val clauses = Parser.parseProlog(prologCode)
    //clauses.forEach { println(it) }
    val mapping = createMapping(clauses)

    mapping.forEach { (name, index) ->
        println("$name: $index")
    }
    println("Knowledge base:")
    clauses.filter { it.body.isEmpty() }.forEach { clause ->
        println(clause.head.encode(mapping))
    }
    val knowledgeBase = clauses.filter { it.body.isEmpty() }
    println("Rules:")
    val rules = clauses.filter { it.body.isNotEmpty() }.groupBy { it.head.name }
    rules.forEach { (name, rules) ->
        println("$name:")
        rules.forEach { rule ->
            println(
                "  ${
                    rule.body.joinToString(" & ") {
                        it.encode(mapping).toString()
                    }
                } => ${rule.head.encode(mapping)}"
            )
        }
    }
    val generated_rule_code = StringBuilder()
    rules.forEach { (name, rule_clauses) ->
        // Find matching terms in the body and the head of the rules
        // For example, if we have the following rule:
        // ancestor(X, Y) :- parent(X, Y).
        // Then the matching terms are:
        // X: goal first argument and first rule first argument
        // Y: goal second argument and first rule second argument
        // We need to find the matching terms because we need to unify them

        // Find matching terms in the body of the rules
        val constraints = mutableListOf<String>()
        rule_clauses.forEach { rule ->
            // Store the positions of the matching terms in the body and the head of the rule
            // It's a list of pairs, where the first element is the position in the body or the head (the head is the 0th index)
            // and the second element is the index of the argument in the predicate
            val termPositions = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
            rule.head.terms.forEachIndexed { headIndex, term ->
                if (term is Variable) {
                    if (!termPositions.containsKey(term.name)) {
                        termPositions[term.name] = mutableListOf()
                    }
                    termPositions[term.name]!!.add(Pair(0, headIndex))
                }
            }
            rule.body.forEachIndexed { bodyIndex, predicate ->
                predicate.terms.forEachIndexed { termIndex, term ->
                    if (term is Variable) {
                        if (!termPositions.containsKey(term.name)) {
                            termPositions[term.name] = mutableListOf()
                        }
                        termPositions[term.name]!!.add(
                            Pair(
                                bodyIndex + 1,
                                termIndex
                            )
                        ) // +1 because the head is the 0th index
                    }
                }
            }

            println(termPositions)
            // From these positions, we can generate the circom code that check the unifications
            // For example, if we have the following rule:
            // ancestor(X, Y) :- parent(X, Z), ancestor(Z, Y).
            // Then the generated code is:
            // assert( goal_args[1] == unification _body[1][1] && goal_args[2] == unification _body[1][2] && unification _body[0][2] == unification _body[1][1])
            // The first two terms are the matching terms in the head, the last term is the matching term in the body


            // Generate the code for the matching terms in the head
            val headUnification = termPositions.map { (name, positions) ->
                val headPositions = positions.filter { it.first == 0 }
                if (headPositions.isNotEmpty()) {
                    val headPosition = headPositions.first()
                    buildList {
                        positions.forEach { position ->
                            if (position.first != 0)
                                add("goal_args[${position.second + 1}] == unified_body[${position.first - 1}][${position.second + 1}]")
                        }

                    }.joinToString(" && ")

                } else {
                    ""
                }
            }.filter { it.isNotEmpty() }


            // Generate the code for the matching terms in the body
            val visited = mutableSetOf<Int>()
            val unifiedBodies = mutableListOf<String>()
            termPositions.forEach { variable, positions ->
                val bodyPositions = positions.filter { it.first != 0 }
                // Match the body positions with each other
                bodyPositions.forEach { bodyPosition ->
                    bodyPositions.forEach { otherBodyPosition ->
                        if (bodyPosition != otherBodyPosition && !visited.contains(bodyPosition.first) && !visited.contains(
                                otherBodyPosition.first
                            )
                        ) {
                            visited.add(bodyPosition.first)
                            unifiedBodies.add("unified_body[${bodyPosition.first - 1}][${bodyPosition.second + 1}] == unified_body[${otherBodyPosition.first - 1}][${otherBodyPosition.second + 1}]")
                        }
                    }
                }
            }
            val allConstraints = (headUnification + unifiedBodies)
            println(allConstraints.joinToString(" && "))
            constraints.add(allConstraints.joinToString(" && "))
        }
        generated_rule_code.appendLine(
            buildString {
                appendLine("template Goal${name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}() {")
                appendLine(
                    "\tsignal input unified_body[${rule_clauses[0].body.size}][3];\n" +
                            "\tsignal input goal_args[3];\n" +
                            "\tsignal output c;\n" +
                            "\tvar result = 0;\n" +
                            "\tvar none = 0;"
                )
                clauses.groupBy { it.head.name }.forEach { (name, _) ->
                    appendLine("\tvar ${name} = ${mapping[name]};")
                }
                appendLine("\n\tgoal_args[0] === ${name};")
                // Number of times we will name to check the knowledge base
                val knowledgeBaseUsage = rule_clauses.filter { rule ->
                    rule.body.size == knowledgeBase.groupBy { it.head.name }
                        .count { rule.body.map { it.name }.contains(it.key) }
                }.sumOf { rule -> rule.body.size }
                if (knowledgeBaseUsage == 1) {
                    appendLine("\tcomponent knowledge = KnowledgeChecker();")
                } else if (knowledgeBaseUsage > 1) {
                    appendLine("\tcomponent knowledge[$knowledgeBaseUsage];")
                    appendLine("\tfor (var i = 0; i < $knowledgeBaseUsage; i++) {")
                    appendLine("\t\tknowledge[i] = KnowledgeChecker();")
                    appendLine("\t}")
                }
                var knowledgeUsageCounter = 0
                var rule_prefix = "\t"
                rule_clauses.forEachIndexed { ind, rule ->
                    append("${rule_prefix}if ( ")
                    var prefix = ""
                    rule.body.forEachIndexed { index, predicate ->
                        append(
                            "${prefix}unified_body[${index}][0] == ${
                                if (Parser.SPECIAL_TERMS.any {
                                        predicate.name.contains(
                                            it
                                        )
                                    }.not()) predicate.name else mapping[predicate.name]
                            }"
                        )
                        prefix = " && "
                    }
                    for (i in rule.body.size until rules.maxOf { it.value.size }) {
                        append("${prefix}unified_body[${i}][0] == none")
                    }
                    appendLine(" ) {")
                    val knowledgeBody =
                        knowledgeBase.groupBy { it.head.name }.count { rule.body.map { it.name }.contains(it.key) }
                    if (rule.body.size == knowledgeBody) {
                        if (knowledgeBaseUsage == 1) {
                            appendLine("\t\tknowledge.a <-- unified_body[0];")
                            appendLine("\t\tresult = knowledge.c;")
                        } else {
                            appendLine("\tresult = 1;")
                            rule.body.forEachIndexed { index, predicate ->
                                appendLine("\tknowledge[$knowledgeUsageCounter].a <-- unified_body[$index];")
                                appendLine("\tresult = result && knowledge[$knowledgeUsageCounter].c;")
                                knowledgeUsageCounter++
                            }
                        }
                    } else {
                        appendLine("\t\tif ( ${constraints[ind]} ) {")
                        appendLine("\t\t\tresult = 1;")
                        appendLine("\t\t}")
                    }
                    append("\t}")
                    rule_prefix = " else "
                }
                appendLine("\n\tc <-- result;")
                appendLine("\tc === 1;")
                appendLine("}")
            }
        )

    } // rules.forEach
    val mapping_code = buildString {
        clauses.groupBy { it.head.name }.forEach { (name, _) ->
            appendLine("\tvar ${name} = ${mapping[name]};")
        }
        appendLine("\tvar true = ${mapping["true"]};")
    }

    val rule_calls = buildString {
        rules.forEach { (name, rules) ->
            appendLine(
                "\tcomponent ${name}Goal = Goal${
                    name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(
                            Locale.getDefault()
                        ) else it.toString()
                    }
                }();"
            )
        }
        val knowledgeAble = knowledgeBase.groupBy { it.head.name }
        if (knowledgeAble.size == 1) {
            appendLine("\tcomponent knowledge = KnowledgeChecker();")
        } else if (knowledgeAble.size > 1) {
            appendLine("\tcomponent knowledge[${knowledgeAble.size}];")
            appendLine("\tfor (var i = 0; i < ${knowledgeAble.size}; i++) {")
            appendLine("\t\tknowledge[i] = KnowledgeChecker();")
            appendLine("\t}")
        }
        var knowledgeUsageCounter = 0
        var rule_prefix = "\t"
        rules.forEach { (name, rule_clauses) ->
            appendLine("${rule_prefix}if(goal_args[0] == ${name}) {")
            appendLine("\t\t${name}Goal.goal_args <-- goal_args;")
            for (i in 0..<rule_clauses[0].body.size) {
                appendLine("\t\t${name}Goal.unified_body[${i}] <-- unified_body[${i}];")
            }
            appendLine("\t\tresult = ${name}Goal.c;")
            append("\t}")
            rule_prefix = " else "
        }
        knowledgeAble.forEach { (name, rules) ->
            if (knowledgeAble.size == 1) {
                appendLine("${rule_prefix}if(goal_args[0] == ${name}) {")
                appendLine("\t\tknowledge.a <-- goal_args;")
                appendLine("\t\tresult = knowledge.c;")
                append("\t}")
            } else {

                appendLine("${rule_prefix}if(goal_args[0] == ${name}) {")
                appendLine("\t\tknowledge[$knowledgeUsageCounter].a <-- goal_args;")
                appendLine("\t\tresult = knowledge[$knowledgeUsageCounter].c;")
                knowledgeUsageCounter++

                append("\t}")

            }
            rule_prefix = " else "
        }
    }

    val transition_constraints = buildString {
        clauses.groupBy { it.head.name }.forEach { (name, _) ->
            appendLine(
                "\tif(currentGoal[0] == $name && (prevUnifiedBodies[0][0] == $name || prevUnifiedBodies[1][0] == $name)) {\n" +
                        "      var has_match = 0;\n" +
                        "      for(var i = 0; i < 2; i++) {\n" +
                        "         if(prevUnifiedBodies[i][1] == currentGoal[1] && prevUnifiedBodies[i][2] == currentGoal[2]) {\n" +
                        "            has_match = 1;\n" +
                        "         }\n" +
                        "      }\n" +
                        "      if(has_match){\n" +
                        "         result = 1;\n" +
                        "      }\n" +
                        "   }"
            )
        }
    }

    val maxDepth = 4;
    val branchingFactor = rules.maxOf { it.value[0].body.size }

    val template = File("template.circom").readText()
    // Padded knowledge base. Each element in the knowledge base shall have a uniform length
    // Specifically, the length of the element with the longest length
    val knowLedgeBasePadded = knowledgeBase.map { clause ->
        val padded = clause.head.encode(mapping).toMutableList()
        while (padded.size < knowledgeBase.maxOf { it.head.encode(mapping).size }) {
            padded.add(0)
        }
        padded
    }

    val generatedCode = template
        .replace("REPLACE_RULE_TEMPLATES", generated_rule_code.toString())
        .replace("REPLACE_PREDICATE_MAPPINGS", mapping_code)
        .replace("REPLACE_RULE_CALLS", rule_calls)
        .replace("REPLACE_KNOWLEDGE_BASE_LEN", knowledgeBase.size.toString())
        .replace("REPLACE_KNOWLEDGE_BASE_ARRAY", knowLedgeBasePadded.joinToString(",") { it.toString() })
        .replace("REPLACE_TRANSITION_RULES", transition_constraints)
        .replace("REPLACE_MAX_DEPTH", maxDepth.toString())
        .replace("REPLACE_BRANCH_FACTOR", branchingFactor.toString())

    File("generated.circom").writeText(generatedCode)

    mapping.forEach { (name, index) ->
        println("'$name': $index,")
    }
    val treeJson = "{\n" +
            "  \"goal\":\"grandfather(john,mary)\",\n" +
            "  \"substitution\": [],\n" +
            "  \"term\": {\"args\": [\"john\", \"mary\" ], \"name\":\"grandfather\"},\n" +
            "  \"unification\": {\n" +
            "    \"body\": [\"male(john)\", \"grandparent(john,mary)\" ],\n" +
            "    \"goal\":\"grandfather(john,mary)\"\n" +
            "  },\n" +
            "  \"ztree\": [\n" +
            "    {\n" +
            "      \"goal\":\"male(john)\",\n" +
            "      \"substitution\": [],\n" +
            "      \"term\": {\"args\": [\"john\" ], \"name\":\"male\"},\n" +
            "      \"unification\": {\"body\": [\"true\" ], \"goal\":\"male(john)\"},\n" +
            "      \"ztree\": [\"true\" ]\n" +
            "    },\n" +
            "    {\n" +
            "      \"goal\":\"grandparent(john,mary)\",\n" +
            "      \"substitution\": [],\n" +
            "      \"term\": {\"args\": [\"john\", \"mary\" ], \"name\":\"grandparent\"},\n" +
            "      \"unification\": {\n" +
            "        \"body\": [\"parent(john,lisa)\", \"parent(lisa,mary)\" ],\n" +
            "        \"goal\":\"grandparent(john,mary)\"\n" +
            "      },\n" +
            "      \"ztree\": [\n" +
            "        {\n" +
            "          \"goal\":\"parent(john,lisa)\",\n" +
            "          \"substitution\": [],\n" +
            "          \"term\": {\"args\": [\"john\", \"lisa\" ], \"name\":\"parent\"},\n" +
            "          \"unification\": {\"body\": [\"true\" ], \"goal\":\"parent(john,lisa)\"},\n" +
            "          \"ztree\": [\"true\" ]\n" +
            "        },\n" +
            "        {\n" +
            "          \"goal\":\"parent(lisa,mary)\",\n" +
            "          \"substitution\": [],\n" +
            "          \"term\": {\"args\": [\"lisa\", \"mary\" ], \"name\":\"parent\"},\n" +
            "          \"unification\": {\"body\": [\"true\" ], \"goal\":\"parent(lisa,mary)\"},\n" +
            "          \"ztree\": [\"true\" ]\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}"

    var tree = ResolutionTree.parseJson(treeJson,mapping)
    println(tree)
    tree = tree.standardize(maxDepth_input = 4, branchingFactor_input = 3,unificationCount_input = 3)
    println(tree.toBFSJson())
}



