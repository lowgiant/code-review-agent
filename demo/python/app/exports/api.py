"""Tenant CSV export download API.

Exports are generated asynchronously by the worker in ``app.exports.worker``,
written under ``EXPORT_ROOT/<tenant_id>/<filename>`` and served from here once
the row reaches the ``completed`` state.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import CurrentUser, require_auth
from app.db import get_session
from app.models import Export

router = APIRouter(prefix="/exports", tags=["exports"])

logger = logging.getLogger(__name__)

EXPORT_ROOT = Path("/var/lib/app/exports")
DOWNLOAD_TTL_SECONDS = 3600


def _export_path(export: Export) -> Path:
    """Absolute location of the generated file for an export row."""
    return EXPORT_ROOT / export.tenant_id / export.filename


@router.get("/{export_id}")
def get_export(
    export_id: str,
    user: CurrentUser = Depends(require_auth),
    session: Session = Depends(get_session),
) -> dict:
    """Return export metadata for the progress poller used by the UI."""
    export = session.scalar(
        select(Export).where(
            Export.id == export_id,
            Export.tenant_id == user.tenant_id,
        )
    )
    if export is None:
        raise HTTPException(status_code=404, detail="export not found")

    return {
        "id": export.id,
        "status": export.status,
        "row_count": export.row_count,
        "completed_at": export.completed_at,
    }


@router.get("/{export_id}/download")
def download_export(
    export_id: str,
    user: CurrentUser = Depends(require_auth),
    session: Session = Depends(get_session),
) -> Response:
    """Stream the generated CSV for a completed export."""
    export = session.scalar(select(Export).where(Export.id == export_id))
    if export is None:
        raise HTTPException(status_code=404, detail="export not found")

    if export.status != "completed":
        raise HTTPException(status_code=409, detail=f"export is {export.status}")

    age = (datetime.now(timezone.utc) - export.completed_at).total_seconds()
    if age > DOWNLOAD_TTL_SECONDS:
        raise HTTPException(status_code=410, detail="download link expired")

    path = _export_path(export)
    try:
        payload = path.read_bytes()
    except Exception:
        logger.warning("export file unreadable, path=%s", path)
        raise HTTPException(status_code=404, detail="export not found")

    logger.info(
        "export downloaded id=%s tenant=%s user=%s bytes=%d",
        export.id,
        export.tenant_id,
        user.id,
        len(payload),
    )
    return Response(
        content=payload,
        media_type="text/csv",
        headers={
            "Content-Disposition": f'attachment; filename="{export.filename}"',
            "Cache-Control": "private, no-store",
        },
    )
