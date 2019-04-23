# Interactive CLI
This module provides support for an "interactive" command-line interface designed mainly for debugging and automation. 
The interface is very basic (no scrolling or copying is possible), but, in exchange, I've added support for custom commands without the need of recompiling.

## Custom commands
Custom commands allow to simplify some operations by acting as aliases. You can specify a file with the `--custom-commands` option, the file stores some JSON values as described below.

#### JSON schema
Every command has these values:
- `startsWith`, the alias name
- `command`, the alias will resolve to this command (use `%s` to specify arguments)
- `arguments`, the number of arguments

Some example commands are already included inside `interactive-cli/commands.json`.


## WIP
As mentioned before, this is very basic. Please open an issue (or PR) if you think something extra is needed.