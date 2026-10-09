// Evaluate the actual production closure without Android Gradle or private files.
class GradleException extends RuntimeException {
    GradleException(String message) { super(message) }
}
def repo = new File(args[0]).canonicalFile
def sandbox = new File(args[1]).canonicalFile
assert sandbox.mkdirs() || sandbox.isDirectory()
def buildText = new File(repo, 'TMessagesProj_AppStandalone/build.gradle').getText('UTF-8')
def marker = 'def validateLumaReleaseSigning = {'
def start = buildText.indexOf(marker)
def end = buildText.indexOf('\ndef lumaReleaseSigningCheck =', start)
assert start >= 0 && end > start: 'Production signing validation closure must be present'
def production = buildText.substring(start, end)
def publicTestKey = new File(sandbox, 'TMessagesProj/config/release.keystore')
publicTestKey.parentFile.mkdirs()
publicTestKey.text = 'public-key-fixture'
def configuredKey = new File(sandbox, 'ci/disposable.jks')
configuredKey.parentFile.mkdirs()
configuredKey.text = 'disposable-key-fixture'
def copiedPublicKey = new File(sandbox, 'ci/copied-public.jks')
copiedPublicKey.bytes = publicTestKey.bytes
def configFile = new File(sandbox, 'local-signing/luma-signing.properties')
configFile.parentFile.mkdirs()
def properties = new Properties()
def binding = new Binding(lumaSigning: properties, lumaSigningFile: configFile,
        rootProject: [file: { String name ->
            def candidate = new File(name)
            candidate.absolute ? candidate : new File(sandbox, name)
        }])
def shell = new GroovyShell(this.class.classLoader, binding)
def validate = shell.evaluate(production + '\nreturn validateLumaReleaseSigning')
def checks = 0
def expect = { boolean allowed, String label ->
    boolean passed = true
    try { validate() } catch (GradleException error) {
        passed = false
        assert !error.message.contains('private-password-fixture'): 'Errors must not include secrets'
    }
    assert passed == allowed: label
    checks++
    println "PASS: $label"
}
expect(false, 'Missing configuration cannot sign a release with the public fallback')
configFile.text = '# non-secret fixture'
expect(false, 'Existing but empty configuration is rejected')
def valid = [storeFile: 'ci/disposable.jks', storePassword: 'private-password-fixture',
             keyAlias: 'fixture', keyPassword: 'private-password-fixture']
valid.each { key, value -> properties.setProperty(key, value) }
expect(true, 'Explicit disposable CI signing remains supported')
valid.keySet().each { key ->
    def saved = properties.remove(key)
    expect(false, "Missing required field $key is rejected")
    properties.setProperty(key, '   ')
    expect(false, "Blank required field $key is rejected")
    properties.setProperty(key, saved)
}
properties.setProperty('storeFile', 'missing.jks')
expect(false, 'Missing configured key is rejected')
properties.setProperty('storeFile', 'TMessagesProj/config/release.keystore')
expect(false, 'Explicit use of the public upstream key is rejected')
properties.setProperty('storeFile', 'TMessagesProj/config/../config/release.keystore')
expect(false, 'A non-canonical path cannot disguise the public key')
properties.setProperty('storeFile', 'ci/copied-public.jks')
expect(false, 'Copying the public key to another filename is rejected')
properties.setProperty('storeFile', configuredKey.absolutePath)
expect(true, 'Absolute configured private/disposable key path is supported')
assert buildText.contains("if (variant.buildType.name != 'debug')")
assert buildText.contains('variant.preBuildProvider.configure { dependsOn(lumaReleaseSigningCheck) }')
assert buildText.contains('doLast { validateLumaReleaseSigning() }')
assert !buildText.substring(0, start).contains('validateLumaReleaseSigning()')
checks += 4
println "PASS: $checks production signing checks. Closure executed with controlled filesystem fixtures; no private keys read, no Gradle build started."
