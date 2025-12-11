// Manually replace every REPLACE_FLAG with the actual value
// Manually replace every REPLACE_FLAG with the actual value
// Manually replace every REPLACE_FLAG with the actual value
// Manually replace every REPLACE_FLAG with the actual value

pipeline {
    agent {
        label 'REPLACE_FLAG'
    }

    environment {
        /* Jenkins VM paths (must map to host) */
        JDK_DIR='REPLACE_FLAG' // JDK location
        JAR_DIR='REPLACE_FLAG' // Maven build output path
        API_DIR='REPLACE_FLAG'   // app.jar location 
        GIT_REPO = 'REPLACE_FLAG'          // Git repository
        DOCKER_IMAGE ='REPLACE_FLAG'      // Image name
        GIT_BRANCH='REPLACE_FLAG'
        REDEPLOY_FILE='REPLACE_FLAG'
    }

    stages {

        stage('Checkout') {
            steps {
                git branch: "${GIT_BRANCH}", url: "${GIT_REPO}"
            }
        }

        // Build with Gradle
        stage('Build By Gradle') {
            steps {
                dir('bookmarkify-api') {
                    sh "./gradlew clean build -Dorg.gradle.java.home=${JDK_DIR}"
                }
            }
        }

        // Remove old backups && back up current app.jar
        stage('Backup app.jar') {
            steps {
                sh 'rm -rf ${API_DIR}/bac/*'
                sh 'rm -rf ${JAR_DIR}/*-plain.jar}'
                sh'''
                    if [ -f "${API_DIR}/app.jar" ]; then
                        mkdir -p "${API_DIR}/bac"
                        mv "${API_DIR}/app.jar" "${API_DIR}/bac/app.jar_bac_$(date +%F)_$(date +%s)"
                    else
                        echo "Source file for backup not found; continue"
                    fi
                '''
            }
        }

        // Send to work dir (adjust match: bookmarkify-api*.jar)
        stage('Move to work dir') {
            steps {
                sh'''
                    FILE=$(find "${JAR_DIR}" -name "bookmarkify-api.jar" -type f -print -quit)
                    if [ -n "$FILE" ]; then
                        mkdir -p "${API_DIR}"
                        cp "$FILE" "${API_DIR}/app.jar"
                        echo "File moved to ${API_DIR}/app.jar"
                    else
                        echo "Maven-built jar file does not exist!" >&2
                        exit 1
                    fi
                '''
            }
        }

        // SSH requires pre-added SSH keys
        stage('Redeploy') {
            steps {
                // sh 'ssh root@172.17.0.1 bash ${REDEPLOY_FILE}'
                sh 'sh ${REDEPLOY_FILE}'
            }
        }
    }
}