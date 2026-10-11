// TEST FIXTURE ONLY. Deliberately not exported as a production storage adapter.
export function memoryRepositoryForTests() {
    let data = { orders: {}, codes: {}, payments: {}, entitlements: {}, limits: {} };
    let tail = Promise.resolve();
    return {
        durable: false,
        snapshot: () => structuredClone(data),
        transaction(action) {
            const run = tail.then(async () => {
                const draft = structuredClone(data);
                const read = value => value === undefined ? undefined : structuredClone(value);
                const tx = {
                    getOrder: id => read(draft.orders[id]),
                    getOrderByRequest: key => read(Object.values(draft.orders).find(o => o.requestKey === key)),
                    getOrderByInvoice: id => read(Object.values(draft.orders).find(o => o.invoiceId === id)),
                    putOrder(order) {
                        for (const previous of Object.values(draft.orders)) {
                            if (previous.id !== order.id && (previous.requestKey === order.requestKey
                                || (order.invoiceId && previous.invoiceId === order.invoiceId))) throw new Error('unique order constraint');
                        }
                        draft.orders[order.id] = read(order);
                    },
                    getCode: hash => read(draft.codes[hash]),
                    putCode: code => { draft.codes[code.hash] = read(code); },
                    getPayment: id => read(draft.payments[id]),
                    putPayment(payment) {
                        if (draft.payments[payment.eventId]) throw new Error('unique payment constraint');
                        draft.payments[payment.eventId] = read(payment);
                    },
                    getEntitlement: id => read(draft.entitlements[id]),
                    putEntitlement: entitlement => { draft.entitlements[entitlement.telegramUserId] = read(entitlement); },
                    consumeRateLimit(bucket, limit, expiresAtMs) {
                        const count = draft.limits[bucket]?.count || 0;
                        if (count >= limit) return false;
                        draft.limits[bucket] = { count: count + 1, expiresAtMs };
                        return true;
                    },
                };
                const result = await action(tx);
                data = draft;
                return read(result);
            });
            tail = run.catch(() => {});
            return run;
        },
    };
}
