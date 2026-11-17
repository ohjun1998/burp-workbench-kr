"""Own one native Strix TUI in a POSIX PTY, without forwarding terminal secrets.

The CLI keeps its own provider/auth/session. Ctrl+O is Strix's real viewer
command; BROWSER hands the per-process viewer capability to browser_capture.py.
No diagnostic stdin or invented CLI flags are used. Only QUIT is accepted from
the parent, and terminal output is drained rather than copied into public logs.
"""
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import selectors
import shlex
import signal
import struct
import sys
import termios
import time


def main():
    if len(sys.argv) != 2 or os.name != "posix":
        return 1
    directory = Path(__file__).absolute().parent
    with open(sys.argv[1], encoding="utf-8") as source:
        command = json.load(source)
    if not isinstance(command, list) or not command or not all(isinstance(v, str) for v in command):
        return 1
    native_pid, terminal = pty.fork()
    if native_pid == 0:
        environment = os.environ.copy()
        environment["TERM"] = "xterm-256color"
        environment["BROWSER"] = " ".join(shlex.quote(value) for value in
            [sys.executable, "-I", str(directory / "browser_capture.py"), "%s"])
        os.execve(command[0], command, environment)
    fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack("HHHH", 42, 150, 0, 0))
    os.set_blocking(terminal, False)
    selector = selectors.DefaultSelector()
    selector.register(terminal, selectors.EVENT_READ, "terminal")
    selector.register(sys.stdin.fileno(), selectors.EVENT_READ, "control")
    stopping = False
    stop_started = None
    quit_step = 0
    control = b""
    began = time.monotonic()
    next_open = began + 1

    def request_stop(_signum=None, _frame=None):
        nonlocal stopping, stop_started
        if not stopping:
            stopping = True
            stop_started = time.monotonic()

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    print("Strix native interactive session started; terminal output stays private.", flush=True)
    try:
        while True:
            reaped, native_status = os.waitpid(native_pid, os.WNOHANG)
            if reaped:
                return os.WEXITSTATUS(native_status) if os.WIFEXITED(native_status) else 128 + os.WTERMSIG(native_status)
            now = time.monotonic()
            # Docker/model warmup can outlast a minute. Keep trying throughout
            # this owned session; Java enforces the approved wall-time limit.
            if not stopping and now >= next_open:
                if not (directory / "viewer-session.json").exists():
                    os.write(terminal, b"\x0f")  # native Ctrl+O: viewer.open
                next_open = now + 2
            if stopping:
                elapsed = now - stop_started
                # Native quit defaults to cancel: select its affirmative action.
                if quit_step == 0:
                    os.write(terminal, b"\x11")
                    quit_step = 1
                elif quit_step == 1 and elapsed >= 0.2:
                    os.write(terminal, b"\t")
                    quit_step = 2
                elif quit_step == 2 and elapsed >= 0.4:
                    os.write(terminal, b"\r")
                    quit_step = 3
                elif quit_step == 3 and elapsed >= 3:
                    os.killpg(native_pid, signal.SIGTERM)
                    quit_step = 4
                elif quit_step == 4 and elapsed >= 4.5:
                    os.killpg(native_pid, signal.SIGKILL)
                    quit_step = 5
            for key, _mask in selector.select(0.1):
                if key.data == "terminal":
                    try:
                        if not os.read(terminal, 65536):
                            selector.unregister(terminal)
                    except OSError as error:
                        if error.errno != errno.EIO:
                            raise
                        selector.unregister(terminal)
                else:
                    data = os.read(sys.stdin.fileno(), 1024)
                    if not data:
                        selector.unregister(sys.stdin.fileno())
                        request_stop()
                    else:
                        control = (control + data)[-4096:]
                        while b"\n" in control:
                            line, control = control.split(b"\n", 1)
                            if line == b"QUIT":
                                request_stop()
    finally:
        selector.close()
        os.close(terminal)
        # A helper failure must not orphan the process group it created.
        try:
            os.killpg(native_pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            os.waitpid(native_pid, 0)
        except ChildProcessError:
            pass


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, TypeError):
        # Exception text and native output can contain credentials/capabilities.
        print("Strix interactive bridge failed; native terminal output was not exposed.", flush=True)
        raise SystemExit(1)
