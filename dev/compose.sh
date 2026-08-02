#!/bin/sh
set -eu

case "$(docker info --format '{{json .SecurityOptions}}' 2>/dev/null)" in
    *rootless*)
        LOCAL_UID=0
        LOCAL_GID=0
        ;;
    *)
        LOCAL_UID="$(id -u)"
        LOCAL_GID="$(id -g)"
        ;;
esac
export LOCAL_UID LOCAL_GID

exec docker compose "$@"
