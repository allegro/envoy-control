package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig
import io.envoyproxy.envoy.config.rbac.v3.Permission
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.extensions.path.match.uri_template.v3.UriTemplateMatchConfig
import io.envoyproxy.envoy.type.matcher.v3.PathMatcher
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType

class RBACFilterPermissions {
    fun createCombinedPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        val permissions = listOfNotNull(
            createMethodPermissions(incomingEndpoint),
            if (incomingEndpoint.paths.isNotEmpty())
                createPathTemplatesPermissionForEndpoint(incomingEndpoint)
            else
                createPathPermissionForEndpoint(incomingEndpoint),
        )
            .map { it.build() }

        return permission().setAndRules(
            Permission.Set.newBuilder().addAllRules(permissions)
        )
    }

   private fun createPathTemplatesPermissionForEndpoint(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        return permission()
            .setOrRules(Permission.Set.newBuilder().addAllRules(
                incomingEndpoint.paths.map(this::createPathTemplate)))
    }

    private fun createPathTemplate(path: String): Permission{
        return permission().setUriTemplate(TypedExtensionConfig.newBuilder()
            .setName("envoy.path.match.uri_template.uri_template_matcher")
            .setTypedConfig(Any.pack(
                UriTemplateMatchConfig.newBuilder()
                    .setPathTemplate(path)
                    .build()
            ))).build()
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
        val matcher = when (matchingType) {
            PathMatchingType.PATH -> StringMatcher.newBuilder().setExact(path).build()
            PathMatchingType.PATH_PREFIX -> StringMatcher.newBuilder().setPrefix(path).build()
            PathMatchingType.PATH_REGEX -> safeRegexMatcher(path)
        }
        return PathMatcher.newBuilder().setPath(matcher).build()
    }
}

private fun safeRegexMatcher(regexPattern: String) = StringMatcher.newBuilder()
    .setSafeRegex(
        RegexMatcher.newBuilder()
            .setRegex(regexPattern)
            .setGoogleRe2(
                RegexMatcher.GoogleRE2.getDefaultInstance()
            )
            .build()
    )
    .build()

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
