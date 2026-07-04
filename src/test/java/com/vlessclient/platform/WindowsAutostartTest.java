package com.vlessclient.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsAutostartTest {

    /** Records every reg.exe invocation and replies with a programmed result. */
    private static final class FakeReg implements WindowsAutostart.CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        private final Function<List<String>, WindowsAutostart.Result> responder;

        FakeReg(Function<List<String>, WindowsAutostart.Result> responder) {
            this.responder = responder;
        }

        @Override
        public WindowsAutostart.Result run(List<String> command) {
            calls.add(List.copyOf(command));
            return responder.apply(command);
        }
    }

    private static WindowsAutostart.Result ok() {
        return new WindowsAutostart.Result(0, "");
    }

    @Test
    void isEnabled_trueWhenRegQuerySucceeds() {
        FakeReg reg = new FakeReg(cmd -> ok());
        assertThat(new WindowsAutostart(reg).isEnabled()).isTrue();
        assertThat(reg.calls.getFirst()).containsSequence("reg", "query");
        assertThat(reg.calls.getFirst()).contains("VlessClient");
    }

    @Test
    void isEnabled_falseWhenRegQueryFails() {
        FakeReg reg = new FakeReg(cmd -> new WindowsAutostart.Result(1,
                "ERROR: The system was unable to find the specified registry key or value."));
        assertThat(new WindowsAutostart(reg).isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseWhenRegExecutableMissing() {
        // When reg.exe cannot even be launched the IOException is swallowed and
        // autostart reports disabled rather than propagating.
        WindowsAutostart.CommandRunner throwing = cmd -> {
            throw new IOException("reg not found");
        };
        assertThat(new WindowsAutostart(throwing).isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_writesRunValueWithLaunchCommand() throws IOException {
        FakeReg reg = new FakeReg(cmd -> ok());

        new WindowsAutostart(reg).setEnabled(true);

        List<String> add = reg.calls.getFirst();
        assertThat(add).containsSequence("reg", "add");
        assertThat(add).containsSequence("/v", "VlessClient");
        assertThat(add).containsSequence("/t", "REG_SZ");
        assertThat(add).contains("/f");
        int dataIndex = add.indexOf("/d") + 1;
        assertThat(add.get(dataIndex)).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void setEnabledTrue_throwsWhenRegAddFails() {
        FakeReg reg = new FakeReg(cmd -> new WindowsAutostart.Result(1, "ERROR: Access is denied."));

        assertThatThrownBy(() -> new WindowsAutostart(reg).setEnabled(true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reg add failed");
    }

    @Test
    void setEnabledFalse_deletesRunValue() throws IOException {
        FakeReg reg = new FakeReg(cmd -> ok());

        new WindowsAutostart(reg).setEnabled(false);

        List<String> delete = reg.calls.getFirst();
        assertThat(delete).containsSequence("reg", "delete");
        assertThat(delete).containsSequence("/v", "VlessClient");
        assertThat(delete).contains("/f");
    }

    @Test
    void setEnabledFalse_missingValueIsNotAnError() {
        FakeReg reg = new FakeReg(cmd -> new WindowsAutostart.Result(1,
                "ERROR: The system was unable to find the specified registry key or value."));

        // A missing value means the goal (no autostart entry) is already met.
        assertThatCode(() -> new WindowsAutostart(reg).setEnabled(false))
                .doesNotThrowAnyException();
    }

    @Test
    void refresh_reinstallsWhenEnabled() throws IOException {
        // First call (query) reports enabled; second call is the add.
        FakeReg reg = new FakeReg(cmd -> ok());

        new WindowsAutostart(reg).refresh();

        assertThat(reg.calls).hasSize(2);
        assertThat(reg.calls.get(0)).containsSequence("reg", "query");
        assertThat(reg.calls.get(1)).containsSequence("reg", "add");
    }

    @Test
    void refresh_doesNothingWhenDisabled() {
        FakeReg reg = new FakeReg(cmd -> new WindowsAutostart.Result(1, "not found"));

        new WindowsAutostart(reg).refresh();

        // Only the query ran; no add attempted.
        assertThat(reg.calls).hasSize(1);
        assertThat(reg.calls.getFirst()).containsSequence("reg", "query");
    }

    @Test
    void launchCommandLine_quotesArgumentsAndNamesLauncher() {
        String line = WindowsAutostart.launchCommandLine();

        assertThat(line).contains("com.vlessclient.app.Launcher");
        assertThat(line).contains("-cp");
    }
}
