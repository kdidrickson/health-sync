import os
from typing import AsyncGenerator

from sqlalchemy import (
    BigInteger, Column, DateTime, Index, String, UniqueConstraint, func
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

# Neon provides postgresql:// or postgres:// — asyncpg needs postgresql+asyncpg://
_raw_url = os.environ["DATABASE_URL"]
ASYNC_DATABASE_URL = (
    _raw_url
    .replace("postgresql://", "postgresql+asyncpg://", 1)
    .replace("postgres://", "postgresql+asyncpg://", 1)
)

engine = create_async_engine(
    ASYNC_DATABASE_URL,
    pool_pre_ping=True,  # handles dropped connections between serverless invocations
    pool_size=5,
    max_overflow=10,
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


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        yield session


async def create_tables() -> None:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
