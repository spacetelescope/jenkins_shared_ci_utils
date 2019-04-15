branch=$(git rev-parse --abbrev-ref HEAD)
sed -i "s/utils@.*'/utils@${branch}'/" Jenkinsfile.test
