package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.stream.*;

public class FileAccess
{
    enum Type {File, Dir}
    public static int MAX_ELEMENT_SIZE = 1024;

    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent;
    private final SymmetricLink parent2meta;
    private final byte[] fileProperties;
    private final Optional<FileRetriever> retriever;

    public FileAccess(byte[] p2m, SortedMap<UserPublicKey, AsymmetricLink> sharingR, byte[] fileProperties, Optional<FileRetriever> retriever)
    {
        this(new SymmetricLink(p2m), sharingR, fileProperties, retriever);
    }

    public FileAccess(FileAccess copy) {
        this(copy.parent2meta, copy.sharingR2parent, copy.fileProperties, copy.retriever);
    }

    public FileAccess(SymmetricLink p2m, SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent, byte[] fileProperties, Optional<FileRetriever> retriever) {
        this.parent2meta = p2m;
        this.sharingR2parent = sharingR2parent;
        this.fileProperties = fileProperties;
        this.retriever = retriever;
    }

    public static FileAccess create(UserPublicKey owner, Set<User> sharingR, SymmetricKey metaKey, SymmetricKey parentKey,
                                    FileProperties fileProperties, Optional<FileRetriever> retriever)
    {
        SortedMap<UserPublicKey, AsymmetricLink> collect = sharingR.stream()
                .collect(Collectors.toMap(x -> new UserPublicKey(x.getPublicKeys()), x -> new AsymmetricLink(x,
                        owner, parentKey), (a, b) -> a, () -> new TreeMap<>()));
        byte[] nonce = metaKey.createNonce();
        return new FileAccess(new SymmetricLink(parentKey, metaKey, parentKey.createNonce()), collect,
                ArrayOps.concat(nonce, metaKey.encrypt(fileProperties.serialize(), nonce)), retriever);
    }

    public static FileAccess create(UserPublicKey owner, SymmetricKey parentKey, FileProperties fileMetadata, Optional<FileRetriever> retriever)
    {
        SymmetricKey metaKey = SymmetricKey.random();
        return create(owner, Collections.EMPTY_SET, metaKey, parentKey, fileMetadata, retriever);
    }

    public Type getType() {
        return Type.File;
    }

    public void serialize(DataOutput dout) throws IOException
    {
        Serialize.serialize(parent2meta.serialize(), dout);
        dout.writeInt(sharingR2parent.size());
        for (UserPublicKey key: sharingR2parent.keySet()) {
            Serialize.serialize(key.getPublicKeys(), dout);
            Serialize.serialize(sharingR2parent.get(key).serialize(), dout);
        }
        Serialize.serialize(fileProperties, dout);
        dout.writeBoolean(retriever.isPresent());
        if (retriever.isPresent())
            retriever.get().serialize(dout);
        dout.write(getType().ordinal());
    }

    public static FileAccess deserialize(DataInput din) throws IOException
    {
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int count = din.readInt();
        SortedMap<UserPublicKey, AsymmetricLink> sharingR = new TreeMap<>();
        for (int i=0; i < count; i++) {
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        }
        byte[] fileProperties = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        boolean hasRetriever = din.readBoolean();
        Optional<FileRetriever> retreiver = hasRetriever ? Optional.of(FileRetriever.deserialize(din)) : Optional.empty();
        FileAccess base = new FileAccess(p2m, sharingR, fileProperties, retreiver);

        Type type = Type.values()[din.readByte() & 0xff];
        if (type == Type.Dir)
            return DirAccess.deserialize(base, din);
        return base;
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getParentKey(User sharingKey, UserPublicKey owner)
    {
        return sharingR2parent.get(sharingKey.toUserPublicKey()).target(sharingKey, owner);
    }

    public FileRetriever getRetriever() {
        return retriever.get();
    }

    public FileProperties getFileProperties(SymmetricKey parentKey) throws IOException
    {
        byte[] nonce = Arrays.copyOfRange(fileProperties, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
        byte[] cipher = Arrays.copyOfRange(fileProperties, TweetNaCl.SECRETBOX_NONCE_BYTES, fileProperties.length);
        return FileProperties.deserialize(getMetaKey(parentKey).decrypt(cipher, nonce));
    }
}
