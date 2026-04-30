# Health Sync Service

FastAPI service that receives health data from the Android app and serves it via REST endpoints. Deployed as a Vercel serverless function backed by Neon Postgres.

## Local development

```bash
# From the service/ directory
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Create a .env file with your Neon connection string
echo 'DATABASE_URL=postgresql://user:pass@host/db' > .env
echo 'WEBHOOK_SECRET=dev-secret' >> .env

uvicorn api.index:app --reload
```

Browse the auto-generated API docs at http://localhost:8000/docs.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/webhook` | `X-Webhook-Secret` header | Receive health data from Android |
| `GET` | `/health` | — | Service info + record counts |
| `GET` | `/health/summary` | — | Latest record per data type |
| `GET` | `/health/latest/{data_type}` | — | Single most-recent record |
| `GET` | `/health/{data_type}` | — | Paginated records (14-day default) |
| `GET` | `/webhook/test` | — | Sample payload structure |

## Deployment (Vercel)

1. Push repo to GitHub.
2. Import project in Vercel.
3. Set environment variables:
   - `DATABASE_URL` — Neon pooled connection string
   - `WEBHOOK_SECRET` — shared secret matching `WEBHOOK_SECRET` in `android/local.properties`
4. Deploy. The `vercel.json` routes all traffic to `api/index.py`.

The `health_records` table is created automatically on the first webhook POST.

## Database schema

```sql
CREATE TABLE health_records (
    id          BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    data_type   VARCHAR(50)   NOT NULL,
    data_timestamp TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    payload     JSONB         NOT NULL,
    CONSTRAINT uq_health_type_ts UNIQUE (data_type, data_timestamp)
);
CREATE INDEX ix_health_type_ts_desc ON health_records (data_type, data_timestamp DESC);
```
