-- Um banco e um usuário por serviço: cada serviço é dono dos próprios dados
-- (docs/architecture.md, seção 5). Senhas apenas para desenvolvimento local.

CREATE DATABASE IF NOT EXISTS transaction_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS fraud_db       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'transaction_user'@'%' IDENTIFIED BY 'transaction_dev';
CREATE USER IF NOT EXISTS 'fraud_user'@'%'       IDENTIFIED BY 'fraud_dev';
CREATE USER IF NOT EXISTS 'notification_user'@'%' IDENTIFIED BY 'notification_dev';

GRANT ALL PRIVILEGES ON transaction_db.*  TO 'transaction_user'@'%';
GRANT ALL PRIVILEGES ON fraud_db.*        TO 'fraud_user'@'%';
GRANT ALL PRIVILEGES ON notification_db.* TO 'notification_user'@'%';

FLUSH PRIVILEGES;
