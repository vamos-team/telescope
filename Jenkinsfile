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
    stage('Backend Test') {
      environment {
        COMPOSE_PROJECT_NAME = "telescope-ci-${env.BRANCH_NAME}-${env.BUILD_NUMBER}".toLowerCase().replaceAll('[^a-z0-9_-]', '-')
        MYSQL_PORT           = '33000-33099'
      }
      steps {
        sh '''
          docker compose up -d --wait mysql
          docker compose run --rm backend-test
        '''
      }
      post {
        always {
          sh 'docker compose down -v || true'
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
      sh 'docker image prune -f || true'
    }
  }
}
