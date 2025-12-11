// Manually replace every REPLACE_FLAG with the actual value
// Manually replace every REPLACE_FLAG with the actual value
// Manually replace every REPLACE_FLAG with the actual value
// Manually replace every REPLACE_FLAG with the actual value
pipeline {
    agent {
        label 'REPLACE_FLAG'
    }

    stages {

        // Update code
        stage('Checkout') {
            steps {
                git branch: "master", url: "git@gitee.com:tcyeee/bookmarkify.git"
            }
        }

        // Build and deploy with Node
        stage('Build by Node') {
            steps {
                dir('bookmarkify-web') {
                    sh 'pnpm install --no-frozen-lockfile'
                    sh 'npm run build'
                }
            }
        }

        // Redeploy
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