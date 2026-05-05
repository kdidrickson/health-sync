import os
from typing import AsyncGenerator
from urllib.parse import urlparse, urlencode, parse_qs, urlunparse

from sqlalchemy import (
    BigInteger, Column, DateTime, Index, String, UniqueConstraint, func
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase
from sqlalchemy.pool import NullPool


def _build_async_url(raw: str) -> str:
    """Convert a Neon postgres:// URL to postgresql+asyncpg://, stripping
    parameters asyncpg doesn't accept (sslmode, channel_binding).
    SSL is passed separately via connect_args."""
    url = raw.replace("postgresql://", "postgresql+asyncpg://", 1) \
             .replace("postgres://", "postgresql+asyncpg://", 1)
    parsed = urlparse(url)
    params = parse_qs(parsed.query, keep_blank_values=True)
    for key in ("sslmode", "channel_binding"):
        params.pop(key, None)
    clean = parsed._replace(query=urlencode({k: v[0] for k, v in params.items()}))
    return urlunparse(clean)


ASYNC_DATABASE_URL = _build_async_url(os.environ["DATABASE_URL"])

# NullPool is required for serverless: each invocation opens/closes its own
# connection instead of trying to reuse a pool across cold starts.
# ssl=True is passed via connect_args because asyncpg doesn't accept sslmode in the URL.
engine = create_async_engine(
    ASYNC_DATABASE_URL,
    poolclass=NullPool,
    connect_args={"ssl": True},
)

AsyncSessionLocal = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


class Base(DeclarativeBase):
    pass


class HealthRecord(Base):
    __tablename__ = "health_records"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    data_type = Column(String(50), nullable=False)
    data_timestamp = Column(DateTime(timezone=True), nullable=False)
    received_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    payload = Column(JSONB, nullable=False)

    __table_args__ = (
        UniqueConstraint("data_type", "data_timestamp", name="uq_health_type_ts"),
        Index("ix_health_type_ts_desc", "data_type", data_timestamp.desc()),
    )


_tables_ready = False


async def ensure_tables() -> None:
    global _tables_ready
    if not _tables_ready:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        _tables_ready = True


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    await ensure_tables()
    async with AsyncSessionLocal() as session:
        yield session


# Keep for explicit calls
async def create_tables() -> None:
    await ensure_tables()
