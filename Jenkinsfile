pipeline {
  agent any

  options {
    disableConcurrentBuilds()
  }

  environment {
    BACKEND_IMAGE_NAME  = 'telescope-backend'
    FRONTEND_IMAGE_NAME = 'telescope-frontend'
    DEPLOY_DIR          = '/opt/telescope'
  }

  stages {
    // DB 테스트는 Testcontainers가 빌드마다 임의 이름·포트의 MySQL 컨테이너를 띄우고 정리
    stage('Backend Test') {
      steps {
        dir('backend') {
          sh 'chmod +x gradlew && ./gradlew test --no-daemon'
        }
      }
    }

    // 프론트 빌드(npm run build)는 frontend/Dockerfile 안에서 함께 검증된다.
    stage('Docker Build') {
      steps {
        script { env.TAG = env.GIT_COMMIT.take(7) }
        sh '''
          docker build -t $BACKEND_IMAGE_NAME:$TAG backend
          docker build -t $FRONTEND_IMAGE_NAME:$TAG frontend
        '''
      }
    }

    // 같은 서버의 도커에 방금 빌드한 이미지로 바로 띄운다 (레지스트리 없음)
    stage('Deploy') {
      when { expression { env.BRANCH_NAME == 'main' || env.GIT_BRANCH in ['origin/main', 'main'] } }
      steps {
        sh '''
          mkdir -p $DEPLOY_DIR
          cp docker-compose.yml $DEPLOY_DIR/docker-compose.yml
          cd $DEPLOY_DIR
          export BACKEND_IMAGE=$BACKEND_IMAGE_NAME:$TAG
          export FRONTEND_IMAGE=$FRONTEND_IMAGE_NAME:$TAG
          docker compose up -d --no-build backend frontend
        '''
      }
    }
  }

  post {
    always { 
      script {
        def isMain = env.BRANCH_NAME == 'main' || env.GIT_BRANCH in ['origin/main', 'main']
        if (env.TAG && !isMain) {
          sh 'docker rmi $BACKEND_IMAGE_NAME:$TAG $FRONTEND_IMAGE_NAME:$TAG || true'
        }
      }
      sh 'docker image prune -f || true'
    }
  }
}
