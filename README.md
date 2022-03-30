# Handy

Some handy scripts (babashka mostly) for various purposes.

## Install

You need to have [`bb`](https://github.com/babashka/babashka#quickstart),
[`nbb`](https://github.com/babashka/nbb#usage) and
[`npm`](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
installed.

Check out the repo locally `git clone git@github.com:tggreene/handy.git` and
symlink the scripts you want into your `PATH` from the `bin` directory.

You may need to `npm install` for some modules (e.g. `ecs-exec`), but you should
be able to symlink bin files into something like `~/.local/bin`.

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


## Notes

- Why are these so badly named?

    I'm sorry I'm trying to do better
