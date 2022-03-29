# ecs-exec

This is a small utility to help connect to ECS container instances. It loads
clusters, tasks and task containers from AWS and allows you to pick them via
their plain names via option flags (opting for the first instance in the case of
multiple instances).

You can specify cli options or use an interactive prompt written in
[`ink`](https://github.com/vadimdemedes/ink).

## Install

Make sure you run `npm install` in the module before running `ecs-exec`.

## Example

![demo](./demo.svg)

