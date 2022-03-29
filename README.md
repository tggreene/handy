# Handy

Some handy scripts (babashka mostly) for various purposes.

## Scripts

### [`ecs-exec`](modules/envx)

Pick out a container for your cluster to shell or port forward to interactively.
Simplifies what can be a notty invokation with vanilla aws cli.

### [`envx`](modules/envx)

Create dynamic environments from simple config files, pull in configuration and
secrets from other programs, network or files.

### [`loke`](modules/envx)

"Local invoke", keep a stash of frequently run _personal_ development tasks
using incantations that you'd prefer not to burden others with.

### [`watchx`](modules/envx)

Simple watch command that listens for file changes.

## Installing

You may need to `npm install` for some modules, but you should be able to
symlink bin files into something like `~/.local/bin`.

## Notes

- Why are these so badly named?

I'm sorry I'm trying to do better
