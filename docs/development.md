# Development

Envoy Control is a [Kotlin](https://kotlinlang.org/) application, it requires JDK 8+ to run it.

## Running
```bash
./gradlew run
```

## Testing
* All tests (unit and integration)
```./gradlew test```
* Unit 
```./gradlew unitTest```
* Integration
```./gradlew integrationTest```
* Reliability tests
```./gradlew clean -i -Penvironment=integration :envoy-control-tests:reliabilityTest -DRELIABILITY_FAILURE_DURATION_SECONDS=20```

## Running Lua tests locally (not inside docker) for debugging purposes

If for some reason `busted` exists with non-zero code and does not give any output you can try running it locally.

### Requirements on macOS

```bash
brew info luarocks
luarocks install busted
```

### Running Lua tests

```
busted --lpath="./envoy-control-core/src/main/resources/lua/?.lua"  envoy-control-tests/src/main/resources/lua_spec/
```

## Packaging
To build a distribution package run
```
./gradle distZip
```
The package should be available in `{root}/envoy-control-runner/build/distributions/envoy-control-runner-{version}.zip`

## Formatter
To apply [ktlint](https://ktlint.github.io/) formatting rules to IntelliJ IDEA. Run: `./gradlew ktlintApplyToIdea`

## Linter
A linter - [detekt](https://detekt.github.io/detekt/) runs when Envoy Control is built. You can run it separately:
`./gradlew detekt`.

