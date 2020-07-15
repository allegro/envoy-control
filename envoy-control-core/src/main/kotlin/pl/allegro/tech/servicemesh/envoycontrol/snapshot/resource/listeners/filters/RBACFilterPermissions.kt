package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.type.matcher.PathMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType

class RBACFilterPermissions {
    fun createCombinedPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        val combinedPermissions = Permission.newBuilder()
        val pathPermission = createPathPermissionForEndpoint(incomingEndpoint)

        if (incomingEndpoint.methods.isNotEmpty()) {
            val methodPermissions = createMethodPermissions(incomingEndpoint)
            combinedPermissions.setAndRules(
                    Permission.Set.newBuilder().addAllRules(listOf(
                            pathPermission.build(),
                            methodPermissions.build()
                    ))
            )
        } else {
            combinedPermissions.setAndRules(
                    Permission.Set.newBuilder().addAllRules(listOf(pathPermission.build()))
            )
        }

        return combinedPermissions
    }

    fun createPathPermission(path: String, matchingType: PathMatchingType): Permission.Builder {
        return Permission.newBuilder().setUrlPath(createPathMatcher(path, matchingType))
    }

    private fun createPathPermissionForEndpoint(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        return createPathPermission(incomingEndpoint.path, incomingEndpoint.pathMatchingType)
    }

    private fun createMethodPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        val methodPermissions = Permission.newBuilder()
        val methodPermissionSet = Permission.Set.newBuilder()
        val methodPermissionsList = incomingEndpoint.methods.map(this::mapMethodToHeaderMatcher)
        methodPermissionSet.addAllRules(methodPermissionsList)
        methodPermissions.setOrRules(methodPermissionSet.build())

        return methodPermissions
    }

    private fun mapMethodToHeaderMatcher(method: String): Permission {
        val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
        return Permission.newBuilder().setHeader(methodMatch).build()
    }

    private fun createPathMatcher(path: String, matchingType: PathMatchingType): PathMatcher {
        val matcher = when (matchingType) {
            PathMatchingType.PATH -> StringMatcher.newBuilder().setExact(path).build()
            PathMatchingType.PATH_PREFIX -> StringMatcher.newBuilder().setPrefix(path).build()
        }
        return PathMatcher.newBuilder().setPath(matcher).build()
    }
}

private fun permission() = Permission.newBuilder()
private fun not(permission: Permission.Builder) = permission().setNotRule(permission)
fun or(permissions: Iterable<Permission>): Permission.Builder = permission()
    .setOrRules(Permission.Set.newBuilder().addAllRules(permissions))
fun not(permissions: Iterable<Permission>): Permission.Builder =
    if (permissions.count() > 0) {
        not(or(permissions))
    } else {
        permission().setAny(true)
    }
