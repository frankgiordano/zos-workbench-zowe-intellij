package org.zowe.explorerjava;

import zowe.client.sdk.core.SshConnection;
import zowe.client.sdk.core.ZosConnection;
import zowe.client.sdk.zosconsole.methods.ConsoleCmd;
import zowe.client.sdk.zosconsole.ConsoleConstants;
import zowe.client.sdk.zosconsole.input.ConsoleCmdInputData;
import zowe.client.sdk.zosconsole.response.ConsoleCmdResponse;
import zowe.client.sdk.zostso.input.StartTsoInputData;
import zowe.client.sdk.zostso.methods.TsoCmd;
import zowe.client.sdk.zostso.methods.TsoStart;
import zowe.client.sdk.zostso.methods.TsoStop;
import zowe.client.sdk.zostso.response.TsoStartResponse;
import zowe.client.sdk.zosuss.method.UssCmd;

import java.util.List;

/**
 * Command facade for TSO, MVS console, and USS SSH command execution.
 * <p>
 * TSO sessions are explicitly started and kept alive so repeated commands use
 * TsoCmd.issueCommandByTsoSessionId instead of creating a new address space per command.
 */
public final class CommandService implements AutoCloseable {
    private final ZosConnection zosConnection;
    private final String tsoAccount;
    private final SshConnection sshConnection;
    private final int sshTimeoutMillis;

    private static final String USS_CWD_MARKER = "__ZWB_CWD__";

    private String ussCwd;
    private TsoCmd tsoCmd;
    private String tsoSessionId;

    public CommandService(ZosConnection zosConnection,
                          String tsoAccount,
                          SshConnection sshConnection,
                          int sshTimeoutMillis) {
        this.zosConnection = zosConnection;
        this.tsoAccount = tsoAccount;
        this.sshConnection = sshConnection;
        this.sshTimeoutMillis = sshTimeoutMillis;
    }

    public synchronized String startTsoSession() throws Exception {
        if (isTsoSessionActive()) return tsoSessionId;
        if (tsoAccount == null || tsoAccount.isBlank()) {
            throw new IllegalStateException("Configure a TSO account number first.");
        }

        StartTsoInputData input = new StartTsoInputData();
        input.setAccount(tsoAccount.trim());
        TsoStartResponse startResponse = new TsoStart(zosConnection).start(input);
        if (!startResponse.isSuccess()) {
            throw new IllegalStateException("TSO logon did not complete successfully: " + startResponse.getResponse());
        }

        tsoSessionId = startResponse.getSessionId();
        tsoCmd = new TsoCmd(zosConnection, tsoAccount.trim());
        // Drain READY/logon noise exactly as TsoCmd.issueCommand does before the user command.
        tsoCmd.drainLogonPrompt(startResponse);
        return tsoSessionId;
    }

    public synchronized List<String> issueTso(String command) throws Exception {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("TSO command is required.");
        String session = startTsoSession();
        return tsoCmd.issueCommandByTsoSessionId(session, command.trim());
    }

    public synchronized void stopTsoSession() throws Exception {
        if (!isTsoSessionActive()) return;
        try {
            new TsoStop(zosConnection).stop(tsoSessionId);
        } finally {
            tsoSessionId = null;
            tsoCmd = null;
        }
    }

    public synchronized boolean isTsoSessionActive() {
        return tsoSessionId != null && !tsoSessionId.isBlank();
    }

    public synchronized String getTsoSessionId() {
        return tsoSessionId;
    }

    public String issueConsole(String command) throws Exception {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Console command is required.");
        // processResponse converts the \r line separators z/OSMF returns into \n.
        ConsoleCmdInputData input = new ConsoleCmdInputData(command.trim());
        input.setProcessResponse();
        ConsoleCmdResponse response = new ConsoleCmd(zosConnection)
                .issueCommandCommon(ConsoleConstants.RES_DEF_CN, input);
        String output = response.getCmdResponse();
        return output == null || output.isBlank() ? response.toString() : output;
    }

    /**
     * Each UssCmd call is a fresh SSH exec session, so the working directory is tracked here:
     * it is restored before every command and re-read (via pwd) after it, which makes
     * {@code cd} persist between commands. A failed cd leaves the directory unchanged.
     */
    public synchronized String issueUss(String command) throws Exception {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("USS command is required.");

        // UssCmd only captures stdout, so stderr is merged into stdout to surface errors.
        StringBuilder script = new StringBuilder();
        if (ussCwd != null) {
            script.append("cd '").append(ussCwd.replace("'", "'\\''")).append("' 2>/dev/null; ");
        }
        script.append("{ ").append(command.trim()).append("\n} 2>&1; ")
                .append("printf '\\n").append(USS_CWD_MARKER).append("%s\\n' \"$(pwd)\"");

        String raw = new UssCmd(sshConnection).issueCommand(script.toString(), sshTimeoutMillis);

        int idx = raw.lastIndexOf("\n" + USS_CWD_MARKER);
        if (idx < 0) return raw;

        String cwd = raw.substring(idx + 1 + USS_CWD_MARKER.length()).trim();
        if (!cwd.isEmpty()) ussCwd = cwd;
        return raw.substring(0, idx);
    }

    public synchronized String getUssCwd() {
        return ussCwd;
    }

    @Override
    public void close() {
        try {
            stopTsoSession();
        } catch (Exception ignored) {
            // Best-effort cleanup during IDE/project disposal.
        }
    }
}
