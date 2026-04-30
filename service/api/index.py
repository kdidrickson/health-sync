import os
import sys
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from mangum import Mangum
from sqlalchemy import desc, func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

# Make the parent directory importable when Vercel runs from repo root
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from database import HealthRecord, create_tables, get_db
from schemas import HealthRecordOut, ServiceInfo, SummaryItem, WebhookRequest

app = FastAPI(
    title="Health Sync Service",
    version="1.0.0",
    description="Receives and serves health data from Terra-connected Android devices",
)

# Mangum translates Vercel/Lambda events into ASGI calls.
# lifespan="off" prevents startup/shutdown event issues in serverless environments.
handler = Mangum(app, lifespan="off")

_WEBHOOK_SECRET = os.environ.get("WEBHOOK_SECRET", "")


def _verify_secret(x_webhook_secret: str = Header(...)) -> None:
    if not _WEBHOOK_SECRET or x_webhook_secret != _WEBHOOK_SECRET:
        raise HTTPException(status_code=401, detail="Invalid webhook secret")


# ---------------------------------------------------------------------------
# POST /webhook
# ---------------------------------------------------------------------------
@app.post("/webhook", status_code=200)
async def receive_webhook(
    body: WebhookRequest,
    db: AsyncSession = Depends(get_db),
    _: None = Depends(_verify_secret),
) -> dict:
    for record in body.records:
        payload = record.data if isinstance(record.data, dict) else {"raw": record.data}
        stmt = (
            pg_insert(HealthRecord)
            .values(
                data_type=record.type,
                data_timestamp=body.synced_at,
                payload=payload,
            )
            .on_conflict_do_update(
                constraint="uq_health_type_ts",
                set_={
                    "payload": pg_insert(HealthRecord).excluded.payload,
                    "received_at": func.now(),
                },
            )
        )
        await db.execute(stmt)

    await db.commit()

    # Ensure tables exist on first deploy (idempotent DDL)
    await create_tables()

    return {"status": "ok"}


# ---------------------------------------------------------------------------
# GET /health/summary  — must come before /health/{data_type}
# ---------------------------------------------------------------------------
@app.get("/health/summary", response_model=list[SummaryItem])
async def get_summary(db: AsyncSession = Depends(get_db)) -> list[SummaryItem]:
    subq = (
        select(
            HealthRecord.data_type,
            func.max(HealthRecord.data_timestamp).label("latest_timestamp"),
            func.count(HealthRecord.id).label("count"),
        )
        .group_by(HealthRecord.data_type)
        .subquery()
    )
    result = await db.execute(select(subq))
    return [
        SummaryItem(
            data_type=row.data_type,
            latest_timestamp=row.latest_timestamp,
            count=row.count,
        )
        for row in result.all()
    ]


# ---------------------------------------------------------------------------
# GET /health/latest/{data_type}  — must come before /health/{data_type}
# ---------------------------------------------------------------------------
@app.get("/health/latest/{data_type}", response_model=HealthRecordOut)
async def get_latest(
    data_type: str,
    db: AsyncSession = Depends(get_db),
) -> HealthRecord:
    result = await db.execute(
        select(HealthRecord)
        .where(HealthRecord.data_type == data_type)
        .order_by(desc(HealthRecord.data_timestamp))
        .limit(1)
    )
    record = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(status_code=404, detail=f"No records for type '{data_type}'")
    return record


# ---------------------------------------------------------------------------
# GET /health/{data_type}
# ---------------------------------------------------------------------------
@app.get("/health/{data_type}", response_model=list[HealthRecordOut])
async def get_health_records(
    data_type: str,
    start_date: Optional[datetime] = Query(default=None),
    end_date: Optional[datetime] = Query(default=None),
    db: AsyncSession = Depends(get_db),
) -> list[HealthRecord]:
    now = datetime.now(timezone.utc)
    start = start_date or (now - timedelta(days=14))
    end = end_date or now

    result = await db.execute(
        select(HealthRecord)
        .where(HealthRecord.data_type == data_type)
        .where(HealthRecord.data_timestamp >= start)
        .where(HealthRecord.data_timestamp <= end)
        .order_by(desc(HealthRecord.data_timestamp))
        .limit(500)
    )
    return list(result.scalars().all())


# ---------------------------------------------------------------------------
# GET /health  — service info + counts
# ---------------------------------------------------------------------------
@app.get("/health", response_model=ServiceInfo)
async def service_info(db: AsyncSession = Depends(get_db)) -> ServiceInfo:
    result = await db.execute(
        select(HealthRecord.data_type, func.count(HealthRecord.id).label("cnt"))
        .group_by(HealthRecord.data_type)
    )
    counts = {row.data_type: row.cnt for row in result.all()}
    return ServiceInfo(status="ok", version="1.0.0", counts=counts)


# ---------------------------------------------------------------------------
# GET /webhook/test  — unauthenticated sample payload
# ---------------------------------------------------------------------------
@app.get("/webhook/test")
async def webhook_test() -> dict:
    return {
        "note": "POST the sample_request body to /webhook with X-Webhook-Secret header",
        "sample_request": {
            "records": [
                {
                    "type": "daily",
                    "data": {
                        "steps": 8000,
                        "heart_rate_data": {"avg_hr_bpm": 72, "resting_hr_bpm": 58},
                        "calories_data": {
                            "total_burned_calories": 2100,
                            "active_calories": 450,
                        },
                        "distance_data": {"distance_meters": 6400},
                    },
                },
                {
                    "type": "body",
                    "data": {"weight_kg": 75.5, "body_fat_percentage": 18.2},
                },
                {
                    "type": "sleep",
                    "data": {
                        "duration_asleep_state_seconds": 25200,
                        "sleep_stages": [
                            {"stage": "deep", "duration_seconds": 5400},
                            {"stage": "rem", "duration_seconds": 7200},
                        ],
                    },
                },
            ],
            "synced_at": datetime.now(timezone.utc).isoformat(),
        },
    }
