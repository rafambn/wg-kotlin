# WG-Kotlin

Kotlin Multiplatform WireGuard Implementation

Current library targets: JVM/desktop.

## WG-Kotlin Modules

- `:wg-kotlin`: core orchestration API and contract boundaries.

Architecture checklist: `docs/new-vpn-rebuild/01-architecture-review-checklist.md`  

### Run Sample App

 - Desktop JVM: `./gradlew :sample:composeApp:run`

### Publish to MavenLocal

1) Run `./gradlew :wg-kotlin:publishToMavenLocal`
2) Open `~/.m2/repository/com/rafambn/`

### Publish to MavenCentral

1) Create an account and a namespace on Sonatype:  
   https://central.sonatype.org/register/central-portal/#create-an-account
2) Add developer id, name, email and the project url to  
   `./wg-kotlin/build.gradle.kts`
3) Generate a GPG key:  
   https://getstream.io/blog/publishing-libraries-to-mavencentral-2021/#generating-a-gpg-key-pair
   ```
   gpg --full-gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys XXXXXXXX
   gpg --export-secret-key XXXXXXXX > XXXXXXXX.gpg
   ```
4) Add these lines to `gradle.properties`:
   ```
   signing.keyId=XXXXXXXX
   signing.password=[key password]
   signing.secretKeyRingFile=../XXXXXXXX.gpg
   mavenCentralUsername=[generated username]
   mavenCentralPassword=[generated password]
   ```
5) Run `./gradlew :wg-kotlin:publishAndReleaseToMavenCentral --no-configuration-cache`
