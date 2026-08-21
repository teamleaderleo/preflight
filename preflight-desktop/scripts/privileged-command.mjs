export function privilegedCommand(command, args, uid = process.getuid?.()) {
  if (uid === 0) return { command, args };
  return { command: "sudo", args: [command, ...args] };
}
