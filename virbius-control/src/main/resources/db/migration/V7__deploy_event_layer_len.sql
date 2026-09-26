-- Combined deploy labels (cloud+gateway+edge) are 18 chars; the column was VARCHAR(16).
ALTER TABLE tb_deploy_event MODIFY layer VARCHAR(32);
