import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/** Offline signing for source catalogs and release manifests. Never upload the private key. */
public final class ReleaseSigner {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("keygen")) {
            Path directory = Path.of(args[1]).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path privateFile = directory.resolve("private-key.pk8");
            Path publicFile = directory.resolve("public-key.txt");
            if (Files.exists(privateFile) || Files.exists(publicFile)) throw new IllegalStateException("Keys already exist; refusing overwrite");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            Files.write(privateFile, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.writeString(publicFile, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), StandardOpenOption.CREATE_NEW);
            System.out.println("Keys created. Keep private-key.pk8 offline and outside the project.");
        } else if (args.length == 4 && args[0].equals("sign")) {
            byte[] payload = Files.readAllBytes(Path.of(args[2]));
            if (payload.length == 0 || payload.length > 384 * 1024) throw new IllegalArgumentException("Invalid payload size");
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key);
            signature.update(payload);
            String envelope = "{\"payload\":\"" + Base64.getEncoder().encodeToString(payload) + "\",\"signature\":\"" +
                Base64.getEncoder().encodeToString(signature.sign()) + "\"}\n";
            Files.writeString(Path.of(args[3]), envelope, StandardOpenOption.CREATE_NEW);
            System.out.println("Signed envelope created. Verify it before publishing.");
        } else if (args.length == 1 && args[0].equals("selftest")) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            KeyPair pair = generator.generateKeyPair();
            byte[] bytes = "ProxyX signing self-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate()); signer.update(bytes);
            byte[] signed = signer.sign();
            signer.initVerify(pair.getPublic()); signer.update(bytes);
            if (!signer.verify(signed)) throw new AssertionError("Verification failed");
            bytes[0] ^= 1;
            signer.initVerify(pair.getPublic()); signer.update(bytes);
            if (signer.verify(signed)) throw new AssertionError("Tampering accepted");
            System.out.println("PASS: signature verification and tamper rejection");
        } else {
            throw new IllegalArgumentException("Usage: java ReleaseSigner.java keygen <private-dir> | sign <private-key.pk8> <payload.json> <new-envelope.json> | selftest");
        }
    }
}
