package peergos.shared.crypto.hash;

import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

import java.util.concurrent.CompletableFuture;

public interface Hasher {

    CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    CompletableFuture<byte[]> sha256(byte[] input);

    byte[] blake2b(byte[] input, int outputBytes);

    default CompletableFuture<Multihash> hash(byte[] input, boolean isRaw) {
        return sha256(input)
                .thenApply(h -> Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, h));
    }

    default Multihash identityHash(byte[] input, boolean isRaw) {
        if (input.length > Multihash.MAX_IDENTITY_HASH_SIZE)
            throw new IllegalStateException("Exceed maximum size foridentity multihashes!");
        return Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.id, input);
    }
}
