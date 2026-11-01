CREATE TABLE users (
 id UUID PRIMARY KEY, username VARCHAR(40) UNIQUE NOT NULL, password_hash TEXT NOT NULL,
 display_name VARCHAR(60) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT 'user',
 status VARCHAR(20) NOT NULL DEFAULT 'active', demo BOOLEAN NOT NULL DEFAULT false,
 created_at BIGINT NOT NULL, terms_version VARCHAR(30) NOT NULL DEFAULT '2026-10');
CREATE TABLE sessions (token_hash VARCHAR(64) PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires_at BIGINT NOT NULL, created_at BIGINT NOT NULL);
CREATE INDEX sessions_expiry ON sessions(expires_at);
CREATE TABLE identities(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),provider VARCHAR(30) NOT NULL,app_id VARCHAR(100) NOT NULL,subject TEXT NOT NULL,created_at BIGINT NOT NULL,UNIQUE(provider,app_id,subject));
CREATE TABLE profiles(user_id UUID PRIMARY KEY REFERENCES users(id),data JSONB NOT NULL DEFAULT '{}',version INTEGER NOT NULL DEFAULT 1);
CREATE TABLE events(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),actor_id UUID NOT NULL REFERENCES users(id),kind VARCHAR(20) NOT NULL,amount_ml INTEGER,occurred_at BIGINT NOT NULL,drink_type VARCHAR(30),emptying VARCHAR(20),note VARCHAR(1000) NOT NULL DEFAULT '',source VARCHAR(20) NOT NULL DEFAULT 'manual',version INTEGER NOT NULL DEFAULT 1,deleted BOOLEAN NOT NULL DEFAULT false,idempotency_key VARCHAR(100) NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,UNIQUE(user_id,idempotency_key));
CREATE INDEX events_user_time ON events(user_id,occurred_at DESC);
CREATE TABLE event_revisions(id BIGSERIAL PRIMARY KEY,event_id UUID NOT NULL REFERENCES events(id),actor_id UUID NOT NULL,version INTEGER NOT NULL,before_data JSONB NOT NULL,reason VARCHAR(300) NOT NULL,created_at BIGINT NOT NULL);
CREATE TABLE plans(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),version INTEGER NOT NULL,data JSONB NOT NULL,active BOOLEAN NOT NULL DEFAULT true,created_at BIGINT NOT NULL,UNIQUE(user_id,version));
CREATE UNIQUE INDEX plan_one_active ON plans(user_id) WHERE active;
CREATE TABLE tasks(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),plan_id UUID REFERENCES plans(id),kind VARCHAR(20) NOT NULL,due_at BIGINT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'pending',event_id UUID REFERENCES events(id),note VARCHAR(300) NOT NULL DEFAULT '',updated_at BIGINT NOT NULL,UNIQUE(plan_id,kind,due_at));
CREATE INDEX tasks_due ON tasks(status,due_at);
CREATE TABLE notifications(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),task_id UUID REFERENCES tasks(id),title TEXT NOT NULL,body TEXT NOT NULL,seen BOOLEAN NOT NULL DEFAULT false,created_at BIGINT NOT NULL,UNIQUE(task_id));
CREATE TABLE outings(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),title VARCHAR(100) NOT NULL,start_at BIGINT NOT NULL,end_at BIGINT NOT NULL,created_at BIGINT NOT NULL);
CREATE TABLE knowledge(id UUID PRIMARY KEY,title VARCHAR(150) NOT NULL,content TEXT NOT NULL,source VARCHAR(300) NOT NULL,category VARCHAR(30) NOT NULL DEFAULT 'guide',status VARCHAR(20) NOT NULL DEFAULT 'draft',version INTEGER NOT NULL DEFAULT 1,updated_at BIGINT NOT NULL);
CREATE TABLE audit(id BIGSERIAL PRIMARY KEY,actor_id UUID,action VARCHAR(80) NOT NULL,resource_id TEXT,created_at BIGINT NOT NULL);
CREATE TABLE devices(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),name VARCHAR(80) NOT NULL,kind VARCHAR(30) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'reserved',created_at BIGINT NOT NULL);
CREATE TABLE chat_messages(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),role VARCHAR(20) NOT NULL,content TEXT NOT NULL,created_at BIGINT NOT NULL);
CREATE INDEX chat_user_time ON chat_messages(user_id,created_at);
