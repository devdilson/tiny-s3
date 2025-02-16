
export AWS_ACCESS_KEY_ID=12345
export AWS_SECRET_ACCESS_KEY=12345


aws s3 ls --endpoint-url http://localhost:8000 s3://mybucket/  --debug

rclone ls test123:/mybucket   

rclone link test123:/mybucket/README.md --expire 1h --log-level DEBUG


aws s3 presign s3://mybucket/README.md \
--endpoint-url http://localhost:8000 \
--expires-in 3600



aws s3 cp README.md s3://mybucket/ --endpoint-url http://localhost:8000 --debug


aws s3 cp ./TypeScript-Client s3://mybucket/myfolder --endpoint-url http://localhost:8000 --debug --recursive