from pathlib import Path
import re

path = Path('preflight-cli/src/main/java/dev/starsector/preflight/cli/PreflightCli.java')
text = path.read_text(encoding='utf-8')

command_pattern = re.compile(
    r'    static void commandUsage\(String command, java\.io\.PrintStream output\) \{\n'
    r'        output\.println\("Usage:"\);\n'
    r'        for \(String line : USAGE\.get\(command\)\) \{\n'
    r'            output\.println\("  " \+ line\);\n'
    r'        \}\n'
    r'    \}\n'
)
command_replacement = '''    static void commandUsage(String command, java.io.PrintStream output) {
        CliHelp.commandUsage(command, USAGE.get(command), output);
    }
'''
text, command_count = command_pattern.subn(command_replacement, text, count=1)
if command_count != 1:
    raise SystemExit(f'commandUsage patch count was {command_count}, expected 1')

global_pattern = re.compile(
    r'    private static void globalUsage\(java\.io\.PrintStream output\) \{\n'
    r'.*?'
    r'^    \}\n\n'
    r'    private static String commandSummary',
    re.MULTILINE | re.DOTALL,
)
global_replacement = '''    private static void globalUsage(java.io.PrintStream output) {
        CliHelp.globalUsage(output, USAGE, PreflightCli::commandSummary);
    }

    private static String commandSummary'''
text, global_count = global_pattern.subn(global_replacement, text, count=1)
if global_count != 1:
    raise SystemExit(f'globalUsage patch count was {global_count}, expected 1')

path.write_text(text, encoding='utf-8')
