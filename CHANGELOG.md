# Changelog

Lists all changes with user impact.
The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/).

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
