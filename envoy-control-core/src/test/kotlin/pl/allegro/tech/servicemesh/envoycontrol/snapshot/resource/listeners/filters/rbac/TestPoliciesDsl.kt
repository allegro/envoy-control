package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.rbac

@DslMarker
annotation class TestPoliciesDsl

@TestPoliciesDsl
class TestPoliciesBuilderScope {
    private val policies: MutableList<TestPolicyScope> = mutableListOf()

    fun policy(name: String, closure: TestPolicyScope.() -> Unit = {}) {
        policies.add(TestPolicyScope(name).apply(closure))
    }

    override fun toString(): String {
        val policiesString = policies.joinToString(",") {
            it.toString()
        }
        return """
            {
                "policies": {
                    $policiesString
                }
            }
        """
    }
}

@TestPoliciesDsl
class TestPolicyScope(private val policyName: String) {
    private val permissionRules: MutableList<TestPermissionRule> = mutableListOf()
    private val principals: MutableList<TestPrincipalScope> = mutableListOf()

    fun permission(closure: TestPermissionScope.() -> TestPermissionRule) {
        permissionRules.add(TestPermissionScope().run(closure))
    }

    fun principal(closure: TestPrincipalScope.() -> Unit) {
        principals.add(TestPrincipalScope().apply(closure))
    }

    override fun toString(): String {
        val permissionRulesString = permissionRules
            .joinToString(",") { it.toString() }
        val principalsString = principals.map { it.toString() }
            .filter { it.isNotEmpty() }.joinToString(",")
        return """
            "$policyName": {
                "permissions": [
                    $permissionRulesString
                ], 
                "principals": [
                    $principalsString
                ]
            } 
        """
    }
}

@TestPoliciesDsl
class TestPrincipalScope {
    private var principalValue = ""

    fun anyTrue() {
        principalValue = """{ "any": "true"}"""
    }

    fun authenticatedPrincipal(value: String) {
        principalValue = """{
                    "authenticated": {
                      "principal_name": {
                        "exact": "spiffe://$value"
                      }
                    }
                }"""
    }

    override fun toString(): String {
        return principalValue
    }
}

@TestPoliciesDsl
class TestPermissionScope {
    fun pathRule(path: String): TestPermissionRule =
        TestPermissionSingleRule(
            """{
                "url_path": {
                   "path": {
                        "exact": "$path"
                   }
                }
            }"""
        )

    fun methodRule(method: String): TestPermissionRule =
        TestPermissionSingleRule("""{
           "header": {
              "name": ":method",
              "exact_match": "$method"
           }
        }""")

    fun emptyRule(): TestPermissionRule =
        TestPermissionEmptyRule()
}

interface TestPermissionRule {
    operator fun not(): TestPermissionRule =
        TestPermissionSingleRule(
            """{
                "not_rule": $this
            }"""
        )

    infix fun and(rule: TestPermissionRule): TestPermissionRule
    infix fun or(rule: TestPermissionRule): TestPermissionRule
}

class TestPermissionSingleRule(private val ruleString: String) : TestPermissionRule {
    override infix fun and(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionAndRule(listOf(ruleString, rule.toString()))

    override fun or(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionOrRule(listOf(ruleString, rule.toString()))

    override fun toString(): String {
        return ruleString
    }
}

class TestPermissionEmptyRule: TestPermissionRule {
    override fun and(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionAndRule(listOf(rule.toString()))

    override fun or(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionOrRule(listOf(rule.toString()))

    override fun toString(): String {
        return "{}"
    }
}

class TestPermissionAndRule(private val rules: List<String>) : TestPermissionRule {

    override fun and(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionAndRule(rules + rule.toString())

    override fun or(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionOrRule(listOf(this.toString(), rule.toString()))

    override fun toString(): String {
        val rulesString = rules.filter { it !== "{}" && it.isNotEmpty() }.joinToString(",")
        return """
            {
              "and_rules": {
                    "rules": [
                        $rulesString
                    ]
              }
            }
        """
    }
}

class TestPermissionOrRule(private val rules: List<String>) : TestPermissionRule {

    override fun and(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionAndRule(listOf(this.toString(), rule.toString()))

    override fun or(rule: TestPermissionRule): TestPermissionRule =
        TestPermissionOrRule(rules + rule.toString())

    override fun toString(): String {
        val rulesString = rules.filter { it !== "{}" && it.isNotEmpty() }.joinToString(",")
        return """
            {
              "or_rules": {
                    "rules": [
                        $rulesString
                    ]
              }
            }
        """
    }
}

