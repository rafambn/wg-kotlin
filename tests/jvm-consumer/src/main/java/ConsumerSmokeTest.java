import com.rafambn.wgkotlin.util.WireGuardKeys;
import java.util.Arrays;

public class ConsumerSmokeTest {
    public static void main(String[] arguments) throws ClassNotFoundException {
        var libraryJar = WireGuardKeys.class.getProtectionDomain().getCodeSource().getLocation();
        var bindingsJar = Class.forName("uniffi.wg_kotlin_uniffi_boringtun.TunnelSession")
            .getProtectionDomain().getCodeSource().getLocation();
        if (!libraryJar.equals(bindingsJar)) {
            throw new AssertionError("UniFFI bindings must be embedded in the WG-Kotlin JAR");
        }
        var keys = WireGuardKeys.INSTANCE;
        byte[] secret = keys.generateSecretKey();
        byte[] publicKey = keys.generatePublicKey(secret);
        if (secret.length != 32 || publicKey.length != 32 || Arrays.equals(secret, publicKey)) {
            throw new AssertionError("BoringTun key generation failed");
        }
        if (Boolean.getBoolean("verifyAllNativeRuntimes")) {
            String[] resources = {
                "darwin-aarch64/libwg_kotlin_uniffi_boringtun.dylib",
                "darwin-x86-64/libwg_kotlin_uniffi_boringtun.dylib",
                "linux-aarch64/libwg_kotlin_uniffi_boringtun.so",
                "linux-x86-64/libwg_kotlin_uniffi_boringtun.so",
                "win32-x86-64/wg_kotlin_uniffi_boringtun.dll",
            };
            for (String resource : resources) {
                if (ConsumerSmokeTest.class.getClassLoader().getResource(resource) == null) {
                    throw new AssertionError("Missing native runtime: " + resource);
                }
            }
        }
        System.out.println("BoringTun loaded through the wg-kotlin dependency.");
    }
}
