# Shared test-only Android debug key

`vibetoon-debug.p12` is a deliberately public key for debug builds only. The alias is `vibetoon-debug`, and both passwords are `android`. It is not a credential and must never be used for release builds, app-store publication, or any security-sensitive purpose. Anyone can sign an APK with it.

SHA-256 of the signing certificate: `02e8a1d469bb32a6cd30e43659e927068a5daa752bd7055b16a81a36f3b88003`.

All debug builds using this configuration can update one another as long as the application ID is also `com.vibetoon` and Android permits the version upgrade. APKs signed by previous independently generated debug keys are not compatible: back up data before uninstalling; the first transition may require uninstall/reinstall. Do not replace this file casually: doing so breaks subsequent in-place updates.
