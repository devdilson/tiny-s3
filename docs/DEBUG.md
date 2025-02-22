rclone ls tinys3:/ --s3-sdk-log-mode=all --log-level DEBUG --header "Accept-Encoding: identity"

cloudflared tunnel --url http://localhost:8000

rclone copy ./bigfile tinyr:/test --progress --transfers 8 --multi-thread-streams 8

podman run -e SERVER_ENDPOINT=host.docker.internal:8000 -e ACCESS_KEY=12345 -e SECRET_KEY=12345 -e ENABLE_HTTPS=0 minio/mint

podman run -e SERVER_ENDPOINT=host.docker.internal:8000 -e ACCESS_KEY=12345 -e SECRET_KEY=12345 -e ENABLE_HTTPS=0 minio/mint aws-sdk-go
