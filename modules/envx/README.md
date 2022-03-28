# envx

This is a small utility to allow loading environment variables (possibly
invoking programs) and pop a command into a new shell with the newly fetched
environment variables.

It uses `x://` style protocols to perform io operations on a provided value,
where `x://` has been chosen not to conflict with existing protocols and be a
little more explicit.

## Supported "Protocols"

 - [Unix Pass](https://www.passwordstore.org/) via `unix-pass://`
 - Curl via `curl-http://` or `curl-https://`
 - From a file via `load-file://`
 - From another shell command via `exec://`
 

 N.B. Results are `trim`ed

## Example

```
> echo '{"APP_PASSWORD" "unix-pass://app/password"
         "APP_CONFIG" "curl-https://www.appconfig.com/config"}' > envx.edn
> envx env
> ...
  APP_PASSWORD=xyz
  APP_CONFIG={"x": 1}
> envx app
  Password OK...
  Config OK...
```
