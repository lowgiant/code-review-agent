/**
 * Payment provider webhook ingestion.
 *
 * The route is mounted with `express.raw({ type: "application/json" })` so the
 * exact bytes the provider signed are available for verification. The provider
 * retries a delivery for up to 24 hours until it receives a 2xx.
 */

import crypto from "node:crypto";
import type { Request, Response } from "express";

import { db } from "../db";
import { fulfillOrder } from "../fulfillment";
import { logger } from "../logger";
import { sendReceipt } from "../notifications";

const SIGNING_SECRET = process.env.PAYMENTS_SIGNING_SECRET ?? "";
const TOLERANCE_SECONDS = 300;

type PaymentEvent = {
  id: string;
  type: string;
  created: number;
  data: {
    orderId: string;
    amountCents: number;
    currency: string;
    customerEmail: string;
  };
};

function verifySignature(raw: Buffer, header: string | undefined): boolean {
  if (!header) {
    return false;
  }

  const [tsPart, sigPart] = header.split(",");
  const timestamp = Number(tsPart?.split("=")[1]);
  const provided = sigPart?.split("=")[1] ?? "";

  if (!Number.isFinite(timestamp)) {
    return false;
  }
  if (Math.abs(Date.now() / 1000 - timestamp) > TOLERANCE_SECONDS) {
    return false;
  }

  const expected = crypto
    .createHmac("sha256", SIGNING_SECRET)
    .update(`${timestamp}.${raw.toString("utf8")}`)
    .digest("hex");

  return expected === provided;
}

export async function handlePaymentWebhook(req: Request, res: Response) {
  const signature = req.get("x-payment-signature");

  if (!verifySignature(req.body as Buffer, signature)) {
    logger.warn("webhook signature rejected", { signature });
    return res.status(400).json({ error: "invalid signature" });
  }

  const event = JSON.parse((req.body as Buffer).toString("utf8")) as PaymentEvent;
  logger.info("webhook received", { event });

  if (event.type !== "payment.succeeded") {
    return res.status(200).json({ ignored: true });
  }

  const order = await db.orders.findById(event.data.orderId);
  if (!order) {
    return res.status(404).json({ error: "order not found" });
  }

  if (order.status === "paid") {
    return res.status(200).json({ duplicate: true });
  }

  await db.orders.update(order.id, {
    status: "paid",
    paidAmountCents: event.data.amountCents,
    paymentEventId: event.id,
  });

  res.status(200).json({ ok: true });

  await fulfillOrder(order.id);
  sendReceipt(order.id).catch((err) => logger.warn("receipt failed", err));
}
