package peergos.shared.hamt;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ChampWrapper<V extends Cborable> implements ImmutableTree<V>
{
    public static final int BIT_WIDTH = 3;
    public static final int MAX_HASH_COLLISIONS_PER_LEVEL = 4;

    public final ContentAddressedStorage storage;
    private final Hasher writeHasher;
    public final int bitWidth;
    private final Function<ByteArrayWrapper, byte[]> hasher;
    private Pair<Champ<V>, Multihash> root;
    private final Function<Cborable, V> fromCbor;

    public ChampWrapper(Champ<V> root,
                        Multihash rootHash,
                        Function<ByteArrayWrapper, byte[]> hasher,
                        ContentAddressedStorage storage,
                        Hasher writeHasher,
                        int bitWidth,
                        Function<Cborable, V> fromCbor) {
        this.storage = storage;
        this.writeHasher = writeHasher;
        this.hasher = hasher;
        this.root = new Pair<>(root, rootHash);
        this.bitWidth = bitWidth;
        this.fromCbor = fromCbor;
    }

    public static <V extends Cborable> CompletableFuture<ChampWrapper<V>> create(Multihash rootHash,
                                                                                 Function<ByteArrayWrapper, byte[]> hasher,
                                                                                 ContentAddressedStorage dht,
                                                                                 Hasher writeHasher,
                                                                                 Function<Cborable, V> fromCbor) {
        return dht.get(rootHash).thenApply(rawOpt -> {
            if (! rawOpt.isPresent())
                throw new IllegalStateException("Champ root not present: " + rootHash);
            return new ChampWrapper<V>(Champ.fromCbor(rawOpt.get(), fromCbor), rootHash, hasher, dht, writeHasher, BIT_WIDTH, fromCbor);
        });
    }

    public static <V extends Cborable> CompletableFuture<ChampWrapper<V>> create(PublicKeyHash owner,
                                                                                 SigningPrivateKeyAndPublicHash writer,
                                                                                 Function<ByteArrayWrapper, byte[]> hasher,
                                                                                 TransactionId tid,
                                                                                 ContentAddressedStorage dht,
                                                                                 Hasher writeHasher,
                                                                                 Function<Cborable, V> fromCbor) {
        Champ<V> newRoot = Champ.empty(fromCbor);
        byte[] raw = newRoot.serialize();
        return writeHasher.sha256(raw)
                .thenCompose(hash -> dht.put(owner, writer.publicKeyHash, writer.secret.signMessage(hash), raw, tid))
                .thenApply(put -> new ChampWrapper<>(newRoot, put, hasher, dht, writeHasher, BIT_WIDTH, fromCbor));
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    @Override
    public CompletableFuture<Optional<V>> get(byte[] rawKey) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return root.left.get(key, hasher.apply(key), 0, BIT_WIDTH, storage);
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    @Override
    public CompletableFuture<Multihash> put(PublicKeyHash owner,
                                            SigningPrivateKeyAndPublicHash writer,
                                            byte[] rawKey,
                                            Optional<V> existing,
                                            V value,
                                            TransactionId tid) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return root.left.put(owner, writer, key, hasher.apply(key), 0, existing, Optional.of(value),
                BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, hasher, tid, storage, writeHasher, root.right)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    @Override
    public CompletableFuture<Multihash> remove(PublicKeyHash owner,
                                               SigningPrivateKeyAndPublicHash writer,
                                               byte[] rawKey,
                                               Optional<V> existing,
                                               TransactionId tid) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return root.left.put(owner, writer, key, hasher.apply(key), 0, existing, Optional.empty(),
                BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, hasher, tid, storage, writeHasher, root.right)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    private CompletableFuture<Multihash> commit(SigningPrivateKeyAndPublicHash writer, Pair<Champ<V>, Multihash> newRoot) {
        root = newRoot;
        return CompletableFuture.completedFuture(newRoot.right);
    }

    /**
     *
     * @return number of keys stored in tree
     * @throws IOException
     */
    public CompletableFuture<Long> size() {
        return root.left.size(0, storage);
    }

    /**
     *
     * @return true
     * @throws IOException
     */
    public <T> CompletableFuture<T> applyToAllMappings(T identity,
                                                       BiFunction<T, Pair<ByteArrayWrapper, Optional<V>>, CompletableFuture<T>> consumer) {
        return root.left.applyToAllMappings(identity, consumer, storage);
    }
}
