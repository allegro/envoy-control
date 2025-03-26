# Changelog

Lists all changes with user impact.
The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/).

## [0.22.12]
### Changed
- add service tag preference routing 
  - add option to fallback-to-any
- optimize auto service tag routing (don't send config to envoys if disabled, reduce number of metadata ser per route)
- fix and refactor e2e tests

## [0.22.11]
### Changed
- Implemented handling of initialResourcesVersions in DeltaRequest for ADS

## [0.22.10]
### Changed
- whitelist for enabling separated routes for status endpoints

## [0.22.9]
### Changed
- bump control plane version to 1.0.48 to support envoy 1.33.0 version
 
## [0.22.8]
### Changed
- changes for `x-envoy-upstream-service-tags` response header:
    - add configuration property to enable it for all envoys
    - remove support for enabling it per envoy

## [0.22.7]
### Changed
- fixed running e2e tests locally on MacOS
- minor cleaning and updating
- added tests for missing and malformed JWT token scenarios
- separated ingress route for /status* paths

## [0.22.6]
### Changed
- Changed api for pathNormalization

## [0.22.5]
### Changed
- Add possibility to create custom routes

## [0.22.4]
- Added possibility for configuring priorities per service

## [0.22.3]
### Changed
- Changed names of some metrics

## [0.22.2]
### Changed
- Migrated metrics to prometheus

## [0.22.1]
### Changed
- Add blacklisted remote clusters to ignore them during sync

## [0.22.0]
### Changed
- Spring Boot update to 3.3.2

## [0.21.1]
### Changed
- Added additional logs to SimpleCache

## [0.21.0]
### Changed
- Added `paths` field in API to support Glob Patterns

## [0.20.20]
### Changed
- Added service_id property to filter metadata

## [0.20.19]
### Changed
- Added http compression filter properties to node metadata
- 
## [0.20.18]
### Changed
- Added http compression filter configuration

## [0.20.17]
### Fixed
- Fix JWT provider configuration to not impact lds cache
- Add missing methods in lua scripts to remove logs about it

## [0.20.16]
### Changed
- Add JWT failure reason to metadata and use it in jwt-status field on denied requests

## [0.20.15]
### Changed
- Java-control-plane update to 1.0.45

## [0.20.14]
### Changed
- Added test to check circuit breaker metric value
- Set "localityAware=true" for cluster subset config

## [0.20.13]
### Changed
- Added setting: "zonesAllowingTrafficSplitting", so changes in a config would be made only for envoys in that zone
- Fixed setting priority for traffic splitting endpoints, they will be duplicated with higher priorities

## [0.20.12]
### Changed
- Added "trackRemaining" flag to enable possibility of tracking additional circuit breaker metrics
- 
## [0.20.11]
### Changed
- Implemented adding a header for locality weighted load balancing

## [0.20.10]
### Changed
- Implemented locality weighted load balancing


## [0.20.9]
### Changed
- Configurable path normalization

## [0.20.6 - 0.20.8]

### Changed
- Merge slashes in http request
- Feature flag for auditing global snapshot

## [0.20.5]

### Changed
- Added possibility to add response header for weighted secondary cluster 

## [0.20.4]

### Changed
- Fix `shouldAuditGlobalSnapshot` property

## [0.20.3]

### Changed
- Fixed traffic splitting condition check for cluster configuration

## [0.20.2]

### Changed
- Updated property names:  secondaryClusterPostfix is changed to secondaryClusterSuffix, 
- aggregateClusterPostfix is changed to aggregateClusterSuffix

## [0.20.2]

### Changed
- Updated property names:  secondaryClusterPostfix is changed to secondaryClusterSuffix, 
- aggregateClusterPostfix is changed to aggregateClusterSuffix

## [0.20.1]

### Changed
- Implemented configuring traffic splitting and fallback using aggregate cluster functionality

## [0.20.0]

### Changed
- Spring Boot upgraded to 3.1.2
- Java upgraded to 17
- Kotlin upgraded to 1.8.2
- Gradle upgraded to 8.3

### Fixed
- Random port generation for testcontainers

## [0.19.36]

### Changed
- Added debug endpoint, which returns current groups

## [0.19.35]

### Changed
- Adjusted load balancing priorities logic to stick traffic to local DC
- Added warning log for access logs/filter configuration

## [0.19.34]

### Changed
- Implemented possibility for configuring load balancing priorities for DCs

## [0.19.33]

### Changed
- Added compression support for State controller and client

## [0.19.32]

### Changed
- reject request service-tag if it duplicates auto service-tag preference
- add debug information to an egress response: upstream service-tags.
  Enabled by default if auto service-tags feature is used
- decrease log level for no clients in incoming-endpoint

## [0.19.31]

### Changed
- move min & max envoy versions inside artifact to be accessible for dependant projects
- add x-service-tag-preference header to upstream request

## [0.19.30]

### Changed
- add possibility to log custom header in RBAC
- add token information to RBAC logs
- specify min and max supported envoy version
- add option to run tests on specific envoy version, including min and max supported version

## [0.19.29]

### Changed
- add mechanism to store custom data in group

## [0.19.28]

### Changed
- update envoy version to 1.24.0

## [0.19.27]

### Changed
- flaky test fixed
- Auto service tags (proxy settings outgoing.routingPolicy
- remove duplicated routes

## [0.19.26]

### Changed
- Bump consul recipes to fix index handling behavior in edge cases

## [0.19.25]

### Changed
- Prefix for negating values from jwt token used in rbac
- Configurable default clients lists

## [0.19.24]

### Changed
- Bump consul recipies to fit new consul api which has optional port field since 1.10.0

## [0.19.23]

### Changed
- Add functionality to filter observed services

## [0.19.22]

### Changed
- Added support for Delta XDS

## [0.19.21]

### Changed
- Remove enriching (with wrong destination) responses with 405 status code in lua

## [0.19.20]

### Changed
- Lua custom metadata

## [0.19.19]

### Changed
- Added support for Delta XDS

## [0.19.19]

### Changed
- Add default access log filter configuration

## [0.19.18]

### Changed
- Log authority and lua authority in lua filters

## [0.19.17]

### Changed
- Added debug endpoint

## [0.19.16]

### Changed
- Added tcp dumps for downstream

## [0.19.15]

### Changed
- Added flags for lua filters
- 
## [0.19.14]

### Changed
- Update java-control-plane - remove api v2

## [0.19.13]

### Changed
- readiness metric

## [0.19.12]

### Changed
- Added readiness warmup time metric

## [0.19.11]

### Changed
- added possibility to configure RateLimitedRetryBackOff in retry policy

## [0.19.10]

### Changed
- fixed types in retry policy properties

## [0.19.9]

### Changed
- add possibility to configure default retryOn property in retry policies 
- moved release action to step in publishing action

## [0.19.8]

### Changed
- Remove reactor in computing cluster state changes

## [0.19.7]

### Changed
- Fix flaky ConsulClusterStateChangesTest
- Match configured method during oauth authorization.

## [0.19.6]

### Changed
- Access log filters config handled
- Global snapshot auditor added
- updated project java version 11
- updated java-control-plane to version with removed api v2

## [0.19.5]
### Changed 
- Fixed issue with ConcurrentModificationException when using new ServicesState

## [0.19.5]
### Changed
- Refactor reactor in RemoteServices 

## [0.19.3]
### Changed
- Nothing. Empty commit for fix released version.
