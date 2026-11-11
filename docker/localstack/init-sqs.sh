#!/bin/bash
# LocalStack 기동 완료(ready) 시 OCR 트리거 큐를 생성한다 — 로컬 개발용.
# 운영/스테이징 큐는 이 스크립트가 아니라 AWS(콘솔/IaC)로 프로비저닝한다.
# (DLQ는 현재 미도입 — 추후 도입 시 여기에 DLQ 큐 + 소스 큐 redrive policy를 추가한다.)
awslocal sqs create-queue --queue-name ocr-job-queue
