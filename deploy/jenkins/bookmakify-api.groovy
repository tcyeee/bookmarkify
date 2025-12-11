// 手动替换所有REPLACE_FLAG为实际值
// 手动替换所有REPLACE_FLAG为实际值
// 手动替换所有REPLACE_FLAG为实际值
// 手动替换所有REPLACE_FLAG为实际值

pipeline {
    agent {
        label 'REPLACE_FLAG'
    }

    environment {
        /* 以下为jenkins虚拟机中地址(需和宿主机映射) */
        JDK_DIR='REPLACE_FLAG' // JDK位置
        JAR_DIR='REPLACE_FLAG' // maven打包后文件位置
        API_DIR='REPLACE_FLAG'   // app.jar 所在位置 
        GIT_REPO = 'REPLACE_FLAG'          // git地址
        DOCKER_IMAGE ='REPLACE_FLAG'      // 镜像名称
        GIT_BRANCH='REPLACE_FLAG'
        REDEPLOY_FILE='REPLACE_FLAG'
    }

    stages {

        stage('Checkout') {
            steps {
                git branch: "${GIT_BRANCH}", url: "${GIT_REPO}"
            }
        }

        // Gradle打包
        stage('Build By Gradle') {
            steps {
                dir('bookmarkify-api') {
                    sh "./gradlew clean build -Dorg.gradle.java.home=${JDK_DIR}"
                }
            }
        }

        // 删除前期备份 && 备份现有app.jar
        stage('Backup app.jar') {
            steps {
                sh 'rm -rf ${API_DIR}/bac/*'
                sh 'rm -rf ${JAR_DIR}/*-plain.jar}'
                sh'''
                    if [ -f "${API_DIR}/app.jar" ]; then
                        mkdir -p "${API_DIR}/bac"
                        mv "${API_DIR}/app.jar" "${API_DIR}/bac/app.jar_bac_$(date +%F)_$(date +%s)"
                    else
                        echo "用于备份的源文件不存在,继续下一步"
                    fi
                '''
            }
        }

        // 发送到工作目录(需要修改匹配字符: bookmarkify-api*.jar )
        stage('Move to work dir') {
            steps {
                sh'''
                    FILE=$(find "${JAR_DIR}" -name "bookmarkify-api.jar" -type f -print -quit)
                    if [ -n "$FILE" ]; then
                        mkdir -p "${API_DIR}"
                        cp "$FILE" "${API_DIR}/app.jar"
                        echo "文件已移动到 ${API_DIR}/app.jar"
                    else
                        echo "Maven打包的jar文件不存在!" >&2
                        exit 1
                    fi
                '''
            }
        }

        // 其中SSH需要提前添加SSH密钥支持
        stage('Redeploy') {
            steps {
                // sh 'ssh root@172.17.0.1 bash ${REDEPLOY_FILE}'
                sh 'sh ${REDEPLOY_FILE}'
            }
        }
    }
}