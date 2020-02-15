package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.sql.*;
import java.util.*;

public class WriterDataTests {

    @Test
    public void tolerateLoopsInOwnedKeys() throws  Exception {
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        TransactionId test = new TransactionId("dummy");
        ContentAddressedStorage dht = new RAMStorage();
        Connection db = Sqlite.build("::memory::");
        MutablePointers mutable = UserRepository.build(dht, new JdbcIpnsAndSocial(db, new SqliteCommands()));

        SigningKeyPair pairA = SigningKeyPair.insecureRandom();
        PublicKeyHash pubA = ContentAddressedStorage.hashKey(pairA.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerA = new SigningPrivateKeyAndPublicHash(pubA, pairA.secretSigningKey);

        SigningKeyPair pairB = SigningKeyPair.insecureRandom();
        PublicKeyHash pubB = ContentAddressedStorage.hashKey(pairB.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerB = new SigningPrivateKeyAndPublicHash(pubB, pairB.secretSigningKey);

        WriterData wdA = IpfsTransaction.call(pubA, tid -> WriterData.createEmpty(pubA, signerA, dht, hasher, tid), dht).join();
        WriterData wdB = IpfsTransaction.call(pubB, tid -> WriterData.createEmpty(pubB, signerB, dht, hasher, tid), dht).join();

        WriterData wdA2 = wdA.addOwnedKey(pubA, signerA, OwnerProof.build(signerB, pubA), dht, hasher).join();
        wdA2.commit(pubA, signerA, MaybeMultihash.empty(), mutable, dht, hasher, test).join();
        MaybeMultihash bCurrent = wdB.commit(pubB, signerB, MaybeMultihash.empty(), mutable, dht, hasher, test).join().get(pubB).hash;

        Set<PublicKeyHash> ownedByA1 = WriterData.getOwnedKeysRecursive(pubA, pubA, mutable, dht, hasher).join();
        Set<PublicKeyHash> ownedByB1 = WriterData.getOwnedKeysRecursive(pubB, pubB, mutable, dht, hasher).join();

        Assert.assertTrue(ownedByA1.size() == 2);
        Assert.assertTrue(ownedByB1.size() == 1);

        WriterData wdB2 = wdB.addOwnedKey(pubB, signerB, OwnerProof.build(signerA, pubB), dht, hasher).join();
        wdB2.commit(pubB, signerB, bCurrent, mutable, dht, hasher, test).join();

        Set<PublicKeyHash> ownedByA2 = WriterData.getOwnedKeysRecursive(pubA, pubA, mutable, dht, hasher).join();
        Set<PublicKeyHash> ownedByB2 = WriterData.getOwnedKeysRecursive(pubB, pubB, mutable, dht, hasher).join();

        Assert.assertTrue(ownedByA2.size() == 2);
        Assert.assertTrue(ownedByB2.size() == 2);
    }
}