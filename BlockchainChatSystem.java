import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

/**
 * BlockchainChatSystem
 * --------------------
 * College project: multiple "user" chat windows (A, B, C, D, E) all read/write
 * a single shared ledger file (chain_log.txt). Every message is a block, hash
 * chained to the previous one (like a blockchain), so the file is tamper-evident.
 *
 * - Users join with a shared keyword. Wrong keyword -> every other window
 *   independently detects it and votes to remove the intruder (consensus).
 * - A user can "edit" only their own message. Instead of rewriting history,
 *   an EDIT block is appended that supersedes the original -> chain stays
 *   immutable, but everyone sees the updated content and gets notified.
 *
 * Uses only core Java + AWT. No external libraries.
 *
 * Run:   javac BlockchainChatSystem.java
 *        java BlockchainChatSystem
 */
public class BlockchainChatSystem {

    private static final String CHAIN_FILE = "chain_log.txt";
    private static final String CORRECT_KEYWORD = "SPIDERWEB"; // shared secret known to legit users

    public static void main(String[] args) {
        Blockchain blockchain = new Blockchain(CORRECT_KEYWORD, CHAIN_FILE);
        new ControlPanel(blockchain);

        // Auto-launch the 5 legitimate users
        String[] names = {"A", "B", "C", "D", "E"};
        int x = 40, y = 40;
        for (String name : names) {
            ChatWindow w = new ChatWindow(blockchain, name, CORRECT_KEYWORD);
            w.setLocation(x, y);
            x += 60;
            y += 60;
        }
    }

    // ==================== Block ====================
    static class Block {
        final int index;
        final long timestamp;
        final String type;   // GENESIS, JOIN, MSG, EDIT, BAN_VOTE, BAN
        final String user;
        final String payload;
        final String prevHash;
        final String hash;

        Block(int index, long timestamp, String type, String user, String payload, String prevHash, String hash) {
            this.index = index;
            this.timestamp = timestamp;
            this.type = type;
            this.user = user;
            this.payload = payload;
            this.prevHash = prevHash;
            this.hash = hash;
        }

        String toFileLine() {
            String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return index + "~" + timestamp + "~" + type + "~" + user + "~" + encodedPayload + "~" + prevHash + "~" + hash;
        }

        static Block fromFileLine(String line) {
            String[] p = line.split("~", -1);
            String payload = new String(Base64.getDecoder().decode(p[4]), StandardCharsets.UTF_8);
            return new Block(Integer.parseInt(p[0]), Long.parseLong(p[1]), p[2], p[3], payload, p[5], p[6]);
        }
    }

    // ==================== ChainListener ====================
    interface ChainListener {
        // isReplay = true when this block is being replayed from history to a
        // newly-joined window (so it must NOT re-trigger voting side effects)
        void onNewBlock(Block b, boolean isReplay);
    }

    // ==================== Blockchain ====================
    static class Blockchain {
        private final List<Block> chain = new ArrayList<>();
        private final List<ChainListener> listeners = new ArrayList<>();
        private final Set<String> bannedUsers = new HashSet<>();
        private final Map<String, Set<String>> banVotes = new HashMap<>();
        private final String correctKeywordHash;
        private final File file;

        Blockchain(String correctKeyword, String filePath) {
            this.correctKeywordHash = sha256(correctKeyword);
            this.file = new File(filePath);
            loadOrInit();
        }

        private synchronized void loadOrInit() {
            if (file.exists() && file.length() > 0) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.trim().isEmpty()) chain.add(Block.fromFileLine(line));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (chain.isEmpty()) {
                String hash = computeHash(0, 0L, "GENESIS", "SYSTEM", "Genesis Block", "0");
                Block genesis = new Block(0, 0L, "GENESIS", "SYSTEM", "Genesis Block", "0", hash);
                chain.add(genesis);
                appendToFile(genesis);
            }
            // rebuild derived state (who's banned) from any pre-existing history,
            // so restarting the app doesn't "forget" a ban
            for (Block b : chain) {
                if (b.type.equals("BAN")) bannedUsers.add(b.payload);
            }
        }

        synchronized void registerListener(ChainListener l) {
            listeners.add(l);
            // replay history so a newly opened window can display the existing log -
            // isReplay=true means this must NOT cast new votes or re-run side effects
            for (Block b : chain) l.onNewBlock(b, true);
        }

        synchronized boolean isValidKeywordHash(String hash) {
            return correctKeywordHash.equals(hash);
        }

        synchronized String hashKeyword(String keyword) {
            return sha256(keyword);
        }

        synchronized List<Block> snapshot() {
            return new ArrayList<>(chain);
        }

        synchronized Block addBlock(String type, String user, String payload) {
            // the ledger itself refuses further messages/edits from a banned user -
            // this is enforced here regardless of what any single window's UI does
            if ((type.equals("MSG") || type.equals("EDIT")) && bannedUsers.contains(user)) {
                return null;
            }
            Block prev = chain.get(chain.size() - 1);
            int idx = chain.size();
            long ts = System.currentTimeMillis();
            String hash = computeHash(idx, ts, type, user, payload, prev.hash);
            Block b = new Block(idx, ts, type, user, payload, prev.hash, hash);
            chain.add(b);
            appendToFile(b);

            // every open window is notified and reacts independently
            for (ChainListener l : new ArrayList<>(listeners)) l.onNewBlock(b, false);

            handleConsensusLogic(b);
            return b;
        }

        private void handleConsensusLogic(Block b) {
            if (b.type.equals("BAN_VOTE")) {
                String offender = b.payload;
                Set<String> voters = banVotes.computeIfAbsent(offender, k -> new HashSet<>());
                voters.add(b.user);
                int requiredVotes = Math.max(1, listeners.size() - 1); // everyone except the offender
                if (voters.size() >= requiredVotes && !bannedUsers.contains(offender)) {
                    bannedUsers.add(offender);
                    addBlock("BAN", "SYSTEM", offender);
                }
            }
        }

        private void appendToFile(Block b) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
                bw.write(b.toFileLine());
                bw.newLine();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        static String computeHash(int index, long ts, String type, String user, String payload, String prevHash) {
            return sha256(index + "|" + ts + "|" + type + "|" + user + "|" + payload + "|" + prevHash);
        }

        static String sha256(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte bt : digest) sb.append(String.format("%02x", bt));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // Recomputes every hash and checks the chain links - proves tamper-evidence
        synchronized String verifyIntegrity() {
            for (int i = 1; i < chain.size(); i++) {
                Block prev = chain.get(i - 1);
                Block cur = chain.get(i);
                if (!cur.prevHash.equals(prev.hash)) {
                    return "Broken link at block " + cur.index + " (prevHash mismatch)";
                }
                String recomputed = computeHash(cur.index, cur.timestamp, cur.type, cur.user, cur.payload, cur.prevHash);
                if (!recomputed.equals(cur.hash)) {
                    return "Tampering detected at block " + cur.index + " (hash mismatch)";
                }
            }
            return "Chain verified: all " + chain.size() + " blocks intact";
        }
    }

    // ==================== ChatWindow (one per user) ====================
    static class ChatWindow extends Frame implements ChainListener {
        private final Blockchain blockchain;
        private final String username;
        private final TextArea log = new TextArea();
        private final TextField input = new TextField();
        private final TextField editIndexField = new TextField(4);
        private final TextField editTextField = new TextField();
        private final Button sendBtn = new Button("Send");
        private final Button editBtn = new Button("Edit My Message");
        private final Set<Integer> myMessageIndices = new HashSet<>(); // so you can only edit your own
        private boolean kicked = false;

        ChatWindow(Blockchain blockchain, String username, String keywordTyped) {
            super("Chat Window - " + username);
            this.blockchain = blockchain;
            this.username = username;

            setLayout(new BorderLayout(5, 5));
            log.setEditable(false);
            add(log, BorderLayout.CENTER);

            Panel bottom = new Panel(new GridLayout(2, 1));

            Panel sendPanel = new Panel(new BorderLayout());
            sendPanel.add(input, BorderLayout.CENTER);
            sendPanel.add(sendBtn, BorderLayout.EAST);

            Panel editPanel = new Panel(new BorderLayout());
            Panel editLeft = new Panel(new FlowLayout(FlowLayout.LEFT));
            editLeft.add(new Label("Edit msg #:"));
            editLeft.add(editIndexField);
            editPanel.add(editLeft, BorderLayout.WEST);
            editPanel.add(editTextField, BorderLayout.CENTER);
            editPanel.add(editBtn, BorderLayout.EAST);

            bottom.add(sendPanel);
            bottom.add(editPanel);
            add(bottom, BorderLayout.SOUTH);

            sendBtn.addActionListener(e -> sendMessage());
            input.addActionListener(e -> sendMessage());
            editBtn.addActionListener(e -> editMessage());

            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    dispose();
                }
            });

            setSize(420, 380);
            setVisible(true);

            // Check the keyword locally BEFORE registering/joining. We don't need
            // to wait for the other windows' consensus vote to know this user is
            // invalid - that vote is still cast (for the ledger/demo), but this
            // window itself must not behave like a normal chat in the meantime.
            String keywordHash = blockchain.hashKeyword(keywordTyped);
            boolean invalidKeyword = !blockchain.isValidKeywordHash(keywordHash);
            if (invalidKeyword) {
                kicked = true;
                lockControls();
                log.setText("[!] Invalid keyword. Access denied.\n" +
                        "You cannot view the chat history or send messages.\n" +
                        "Waiting for the other users to confirm your removal...\n");
            }

            blockchain.registerListener(this);
            blockchain.addBlock("JOIN", username, keywordHash);

            if (invalidKeyword) {
                showAccessDeniedDialog();
            }
        }

        private void lockControls() {
            input.setEnabled(false);
            sendBtn.setEnabled(false);
            editBtn.setEnabled(false);
            editIndexField.setEnabled(false);
            editTextField.setEnabled(false);
        }

        private void showAccessDeniedDialog() {
            setTitle(username + " (INVALID KEYWORD)");
            Dialog d = new Dialog(this, "Access Denied", true);
            d.setLayout(new BorderLayout(10, 10));
            d.add(new Label("Wrong keyword - you cannot join this chat."), BorderLayout.CENTER);
            Button ok = new Button("OK");
            ok.addActionListener(e -> d.dispose());
            d.add(ok, BorderLayout.SOUTH);
            d.setSize(360, 110);
            d.setLocationRelativeTo(this);
            d.setVisible(true);
        }

        private void sendMessage() {
            if (kicked) return;
            String text = input.getText().trim();
            if (text.isEmpty()) return;
            Block b = blockchain.addBlock("MSG", username, text);
            if (b == null) return; // rejected by the ledger (e.g. you were banned)
            myMessageIndices.add(b.index);
            input.setText("");
        }

        private void editMessage() {
            if (kicked) return;
            try {
                int idx = Integer.parseInt(editIndexField.getText().trim());
                String newText = editTextField.getText().trim();
                if (newText.isEmpty()) return;
                if (!myMessageIndices.contains(idx)) {
                    log.append("[!] You can only edit your own messages.\n");
                    return;
                }
                Block b = blockchain.addBlock("EDIT", username, idx + ":" + newText);
                if (b == null) return; // rejected by the ledger
                editIndexField.setText("");
                editTextField.setText("");
            } catch (NumberFormatException ex) {
                log.append("[!] Enter a valid message number to edit.\n");
            }
        }

        @Override
        public void onNewBlock(Block b, boolean isReplay) {
            // A window that's already locked out (invalid keyword, or banned)
            // must not display replayed history or any further chat activity.
            if (kicked) return;

            switch (b.type) {
                case "JOIN":
                    if (!b.user.equals(username)) {
                        if (!blockchain.isValidKeywordHash(b.payload)) {
                            log.append("[!] " + b.user + " tried to join with a wrong keyword. Voting to remove.\n");
                            if (!isReplay) blockchain.addBlock("BAN_VOTE", username, b.user);
                        } else {
                            log.append("*** " + b.user + " joined the chat ***\n");
                        }
                    }
                    break;
                case "MSG":
                    log.append("[" + b.index + "] " + b.user + ": " + b.payload + "\n");
                    break;
                case "EDIT": {
                    String[] parts = b.payload.split(":", 2);
                    log.append("[edit] " + b.user + " updated message #" + parts[0] + " -> " + parts[1] + "\n");
                    break;
                }
                case "BAN_VOTE":
                    if (!b.user.equals(username)) {
                        log.append("[vote] " + b.user + " voted to remove " + b.payload + "\n");
                    }
                    break;
                case "BAN":
                    log.append("*** " + b.payload + " was removed by consensus ***\n");
                    if (b.payload.equals(username) && !kicked) {
                        kicked = true;
                        terminateWindow();
                    }
                    break;
                default:
                    break;
            }
        }

        private void terminateWindow() {
            lockControls();
            setTitle(username + " (REMOVED)");
            Dialog d = new Dialog(this, "Removed", true);
            d.setLayout(new BorderLayout(10, 10));
            d.add(new Label("You have been removed by consensus of the other users."), BorderLayout.CENTER);
            Button ok = new Button("Close Window");
            ok.addActionListener(e -> {
                d.dispose();
                dispose();
            });
            d.add(ok, BorderLayout.SOUTH);
            d.setSize(360, 110);
            d.setLocationRelativeTo(this);
            d.setVisible(true);
        }
    }

    // ==================== ControlPanel ====================
    static class ControlPanel extends Frame {
        private final Blockchain blockchain;

        ControlPanel(Blockchain blockchain) {
            super("Control Panel");
            this.blockchain = blockchain;
            setLayout(new FlowLayout());

            Button intruderBtn = new Button("Simulate Intruder Join");
            Button verifyBtn = new Button("Verify Chain Integrity");
            Button viewLedgerBtn = new Button("View Raw Ledger");

            add(intruderBtn);
            add(verifyBtn);
            add(viewLedgerBtn);

            intruderBtn.addActionListener(e -> simulateIntruder());
            verifyBtn.addActionListener(e -> showMessage("Integrity Check", blockchain.verifyIntegrity()));
            viewLedgerBtn.addActionListener(e -> showLedger());

            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    System.exit(0);
                }
            });

            setSize(420, 90);
            setLocation(500, 40);
            setVisible(true);
        }

        private void simulateIntruder() {
            Dialog d = new Dialog(this, "Intruder Login", true);
            d.setLayout(new GridLayout(3, 2));
            d.add(new Label("Name:"));
            TextField nameField = new TextField("Mallory");
            d.add(nameField);
            d.add(new Label("Keyword:"));
            TextField keyField = new TextField("wrongword");
            d.add(keyField);
            Button ok = new Button("Join");
            d.add(ok);
            d.add(new Label());
            ok.addActionListener(e -> {
                String name = nameField.getText().trim();
                String key = keyField.getText().trim();
                d.dispose();
                new ChatWindow(blockchain, name, key);
            });
            d.setSize(280, 120);
            d.setLocationRelativeTo(this);
            d.setVisible(true);
        }

        private void showMessage(String title, String msg) {
            Dialog d = new Dialog(this, title, true);
            d.setLayout(new BorderLayout());
            d.add(new Label(msg), BorderLayout.CENTER);
            Button ok = new Button("OK");
            ok.addActionListener(e -> d.dispose());
            d.add(ok, BorderLayout.SOUTH);
            d.setSize(420, 100);
            d.setLocationRelativeTo(this);
            d.setVisible(true);
        }

        private void showLedger() {
            Dialog d = new Dialog(this, "Raw Ledger (chain_log.txt)", true);
            d.setLayout(new BorderLayout());
            TextArea area = new TextArea();
            area.setEditable(false);
            StringBuilder sb = new StringBuilder();
            for (Block b : blockchain.snapshot()) {
                sb.append("#").append(b.index).append(" [").append(b.type).append("] ")
                  .append(b.user)
