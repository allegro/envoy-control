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

## Packaging
To build a distribution package run
```
./gradle distZip
```
The package should be available in `{root}/envoy-control-runner/build/distributions/envoy-control-runner-{version}.zip`

## Formatter
To apply [ktlint](https://ktlint.github.io/) formatting rules to IntelliJ IDEA. Run: `./gradlew ktlintApplyToIdea`

## Linter
A linter - [detekt](https://arturbosch.github.io/detekt/) runs when Envoy Control is built. You can run it separately:
`./gradlew detekt`.

