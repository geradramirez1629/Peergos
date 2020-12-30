package peergos.server.space;

import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

public class QuotaCLI extends Builder {

    private static QuotaAdmin buildQuotaStore(Args a) {
        Supplier<Connection> dbConnectionPool = getDBConnector(a, "transactions-sql-file");
        TransactionStore transactions = buildTransactionStore(a, dbConnectionPool);
        DeletableContentAddressedStorage localStorage = buildLocalStorage(a, transactions);
        JdbcIpnsAndSocial rawPointers = buildRawPointers(a, getDBConnector(a, "mutable-pointers-file", dbConnectionPool));
        MutablePointers localPointers = UserRepository.build(localStorage, rawPointers);
        MutablePointersProxy proxingMutable = new HttpMutablePointers(buildP2pHttpProxy(a), getPkiServerId(a));
        CoreNode core = buildCorenode(a, localStorage, transactions, rawPointers, localPointers, proxingMutable);
        return buildSpaceQuotas(a, localStorage, core,
                getDBConnector(a, "space-requests-sql-file", dbConnectionPool),
                getDBConnector(a, "quotas-sql-file", dbConnectionPool));
    }

    private static void printQuota(String name, long quota) {
        System.out.println(name + " " + formatQuota(quota));
    }

    private static String formatQuota(long quota) {
        long mb = quota / 1024 / 1024;
        if (mb == 0)
            return quota + " B";
        if (mb < 1024)
            return mb + " MiB";
        return mb/1024 + " GiB";
    }

    public static final Command<Boolean> SET = new Command<>("set",
            "Set free quota for a user on this server",
            a -> {
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage)
                    throw new IllegalStateException("Quota CLI only valid on non paid instances");
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "quotas-sql-file");
                JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);

                String name = a.getArg("username");
                long quota = UserQuotas.parseQuota(a.getArg("quota"));
                quotas.setQuota(name, quota);
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "The username to set the quota of", true),
                    new Command.Arg("quota", "The quota in bytes or (k, m, g, t)", true),
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> REQUESTS = new Command<>("requests",
            "Show pending quota requests on this server",
            a -> {
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage)
                    throw new IllegalStateException("Quota CLI only valid on non paid instances");
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "space-requests-sql-file");
                JdbcSpaceRequests reqs = JdbcSpaceRequests.build(quotasDb, sqlCommands);
                List<QuotaControl.LabelledSignedSpaceRequest> raw = reqs.getSpaceRequests();

                NetworkAccess net = Builder.buildLocalJavaNetworkAccess(a.getInt("port")).join();
                List<DecodedSpaceRequest> allReqs = DecodedSpaceRequest.decodeSpaceRequests(raw, net.coreNode, net.dhtClient).join();
                System.out.println("Quota requests:");
                for (DecodedSpaceRequest req : allReqs) {
                    long utcMillis = req.decoded.utcMillis;
                    LocalDateTime reqTime = LocalDateTime.ofEpochSecond(utcMillis/1000, ((int)(utcMillis % 1000)) * 1000_000, ZoneOffset.UTC);
                    String formattedQuota = formatQuota(req.decoded.getSizeInBytes());
                    System.out.println(req.getUsername() + " " + formattedQuota + " " + reqTime);
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> SHOW = new Command<>("show",
            "Show free quota of all users on this server",
            a -> {
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage)
                    throw new IllegalStateException("Quota CLI only valid on non paid instances");
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "quotas-sql-file");
                JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);

                if (a.hasArg("username")) {
                    String name = a.getArg("username");
                    printQuota(name, quotas.getQuota(name));
                    return true;
                }
                TreeMap<String, Long> all = new TreeMap<>(quotas.getQuotas());
                all.forEach(QuotaCLI::printQuota);
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "The user whose quota to show (or all users are shown)", false),
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> TOKEN_CREATE = new Command<>("create",
            "Create tokens for signups",
            a -> {
                Crypto crypto = Main.initCrypto();
                int count = a.getInt("count", 1);
                System.out.println("Created signup tokens:");
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage) {
                    QuotaAdmin quotas = Builder.buildPaidQuotas(a);
                    for (int i=0; i < count; i++)
                        System.out.println(quotas.generateToken(crypto.random));
                }
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "quotas-sql-file");
                JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);

                for (int i=0; i < count; i++) {
                    String token = ArrayOps.bytesToHex(crypto.random.randomBytes(32));
                    quotas.addToken(token);
                    System.out.println(token);
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> TOKENS_LIST = new Command<>("list",
            "Show tokens for signups",
            a -> {
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage)
                    throw new IllegalStateException("Quota CLI only valid on non paid instances");
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "quotas-sql-file");
                JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);

                List<String> tokens = quotas.listTokens();
                System.out.println("Stored tokens:");
                tokens.forEach(System.out::println);
                return true;
            },
            Arrays.asList(
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> TOKEN = new Command<>("token",
            "Create and list tokens for signups",
            a -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            ),
            Arrays.asList(TOKEN_CREATE, TOKENS_LIST)
    );

    public static final Command<Boolean> QUOTA = new Command<>("quota",
            "Manage free quota of users on this server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("print-log-location", "Whether to print the log file location at startup", false, "false"),
                    new Command.Arg("log-to-file", "Whether to log to a file", false, "false"),
                    new Command.Arg("log-to-console", "Whether to log to the console", false, "false")
            ),
            Arrays.asList(SHOW, SET, REQUESTS, TOKEN)
    );
}
