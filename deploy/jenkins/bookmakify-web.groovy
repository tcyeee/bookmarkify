// 手动替换所有REPLACE_FLAG为实际值
// 手动替换所有REPLACE_FLAG为实际值
// 手动替换所有REPLACE_FLAG为实际值
// 手动替换所有REPLACE_FLAG为实际值
pipeline {
    agent {
        label 'REPLACE_FLAG'
    }

    stages {

        // 更新代码
        stage('Checkout') {
            steps {
                git branch: "master", url: "git@gitee.com:tcyeee/bookmarkify.git"
            }
        }

        // Node打包部署
        stage('Build by Node') {
            steps {
                dir('bookmarkify-web') {
                    sh 'pnpm install --no-frozen-lockfile'
                    sh 'npm run build'
                }
            }
        }

        // 重新部署
        stage('Build by Docker') {
            steps {
                dir ('bookmarkify-web') {
                    sh 'docker build -t bookmarkify-web .'
                }
            }
        }
        
        stage("Docker restart"){
            steps{
                sh 'docker compose -f /root/docker/compose.yml up bookmarkify-web -d'
                sh 'docker system prune -f'
            }
        }
    }
}