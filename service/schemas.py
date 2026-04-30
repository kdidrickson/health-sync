from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class WebhookPayload(BaseModel):
    type: str = Field(..., description="daily | body | sleep")
    data: Any = Field(..., description="Raw Terra payload for this data type")


class WebhookRequest(BaseModel):
    records: list[WebhookPayload]
    synced_at: datetime


class HealthRecordOut(BaseModel):
    model_config = {"from_attributes": True}

    id: int
    data_type: str
    data_timestamp: datetime
    received_at: datetime
    payload: Any


class SummaryItem(BaseModel):
    data_type: str
    latest_timestamp: datetime
    count: int


class ServiceInfo(BaseModel):
    status: str
    version: str
    counts: dict[str, int]
