package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.type.matcher.PathMatcher
import io.envoyproxy.envoy.type.matcher.RegexMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType

class RBACFilterPermissions {

    private val paramRegex = Regex("\\{\\w+\\}")

    fun createCombinedPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        val permissions = listOfNotNull(
            createPathPermissionForEndpoint(incomingEndpoint),
            createMethodPermissions(incomingEndpoint)
        )
            .map { it.build() }

        return permission().setAndRules(
            Permission.Set.newBuilder().addAllRules(permissions)
        )
    }

    fun createPathPermission(path: String, matchingType: PathMatchingType): Permission.Builder {
        return permission().setUrlPath(createPathMatcher(path, matchingType))
    }

    private fun createPathPermissionForEndpoint(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        return createPathPermission(incomingEndpoint.path, incomingEndpoint.pathMatchingType)
    }

    private fun createMethodPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder? {
        if (incomingEndpoint.methods.isEmpty()) {
            return null
        }
        val methodPermissionsList = incomingEndpoint.methods.map(this::mapMethodToHeaderMatcher)
        val methodPermissionSet = Permission.Set.newBuilder().addAllRules(methodPermissionsList)
        val methodPermissions = permission().setOrRules(methodPermissionSet.build())
        return methodPermissions
    }

    private fun mapMethodToHeaderMatcher(method: String): Permission {
        val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
        return permission().setHeader(methodMatch).build()
    }

    private fun createPathMatcher(path: String, matchingType: PathMatchingType): PathMatcher {
        val matcher = when (path.contains(paramRegex)) {
            true -> {
                val regexPath = getRegexPath(path, matchingType)
                StringMatcher.newBuilder()
                    .setSafeRegex(
                        RegexMatcher.newBuilder()
                            .setRegex(regexPath)
                            .setGoogleRe2(
                                RegexMatcher.GoogleRE2.getDefaultInstance()
                            )
                            .build()
                    )
                    .build()
            }
            false -> when (matchingType) {
                PathMatchingType.PATH -> StringMatcher.newBuilder().setExact(path).build()
                PathMatchingType.PATH_PREFIX -> StringMatcher.newBuilder().setPrefix(path).build()
            }
        }
        return PathMatcher.newBuilder().setPath(matcher).build()
    }

    private fun getRegexPath(path: String, matchingType: PathMatchingType): String {
        var regexPath = path.replace(paramRegex, "\\\\w+")
        regexPath = regexPath.replace("/", "\\/")
        if (matchingType == PathMatchingType.PATH_PREFIX) {
            regexPath += ".*"
        } else if (matchingType == PathMatchingType.PATH && regexPath.endsWith(".*")) {
            regexPath = regexPath.removeSuffix(".*")
        }
        return regexPath
    }
}

private fun permission() = Permission.newBuilder()
private fun not(permission: Permission.Builder) = permission().setNotRule(permission)
fun anyOf(permissions: Iterable<Permission>): Permission.Builder = permission()
    .setOrRules(Permission.Set.newBuilder().addAllRules(permissions))

fun noneOf(permissions: List<Permission>): Permission.Builder =
    if (permissions.isNotEmpty()) {
        not(anyOf(permissions))
    } else {
        permission().setAny(true)
    }
