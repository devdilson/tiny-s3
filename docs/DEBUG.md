rclone ls tinys3:/ --s3-sdk-log-mode=all --log-level DEBUG --header "Accept-Encoding: identity"

cloudflared tunnel --url http://localhost:8000