/* Runs a command under the seccomp filter an Android app runs under: the syscalls named on the
 * command line are allowed, every other one is blocked with SIGSYS (SECCOMP_RET_TRAP), as
 * Android's zygote does for a syscall bionic does not use. The engine test puts the computer's
 * programs under it (android_policy.py gives the list), so PRoot has to handle them as on a phone.
 *
 * Usage: android-seccomp <nr>[,<nr>...] -- command...
 */
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

#define MAX_SYSCALLS 1024

int main(int argc, char **argv)
{
	static int allowed[MAX_SYSCALLS];
	static struct sock_filter filter[2 * MAX_SYSCALLS + 2];
	struct sock_fprog program;
	int count = 0;
	int length = 0;

	if (argc < 4 || strcmp(argv[2], "--") != 0) {
		fprintf(stderr, "usage: android-seccomp <nr>[,<nr>...] -- command...\n");
		return 2;
	}
	for (char *number = strtok(argv[1], ","); number != NULL; number = strtok(NULL, ",")) {
		if (count == MAX_SYSCALLS) {
			fprintf(stderr, "android-seccomp: more than %d syscalls\n", MAX_SYSCALLS);
			return 2;
		}
		allowed[count++] = atoi(number);
	}

	filter[length++] = (struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
	for (int i = 0; i < count; i++) {
		filter[length++] = (struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, allowed[i], 0, 1);
		filter[length++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
	}
	filter[length++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
	program.len = (unsigned short) length;
	program.filter = filter;

	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
		perror("android-seccomp");
		return 1;
	}
	execvp(argv[3], argv + 3);
	perror("android-seccomp: exec");
	return 127;
}
