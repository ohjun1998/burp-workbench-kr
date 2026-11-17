#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
/* Supply the missing init reaper for isolated process-fixture tests only. */
int main(int argc, char **argv) {
 if (argc < 2 || prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) return 125;
 pid_t child = fork();
 if (child < 0) return 125;
 if (child == 0) {execvp(argv[1], argv + 1);perror("execvp");_exit(127);}
 int status, result = 125;pid_t pid;
 while ((pid = waitpid(-1, &status, 0)) > 0 || errno == EINTR) {
  if (pid == child) result = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
 }
 return result;
}
