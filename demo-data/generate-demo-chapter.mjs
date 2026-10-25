#!/usr/bin/env node
/**
 * Generates DuesDesk-demo-50-member-chapter.json: a simulated chapter with 50 current members and more than two years
 * of dues, bank/Venmo reconciliation, cash flow, recurring bills, chair budgets, house rent and reminder history
 * (Aug 2024 to Oct 2026). Everything is invented; the names, phone numbers and amounts are not real.
 *
 * The output is a plain DuesDesk backup, so it loads through Import & Export > Import backup (no password).
 * The same seed always produces the same file.
 *
 *   node generate-demo-chapter.mjs [output-file]
 */
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const TODAY = '2026-10-25';
const HISTORY_START = '2024-08-01';
const OUT = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), 'DuesDesk-demo-50-member-chapter.json');

// ---------- deterministic helpers ----------
let seed = 20261025;
function rnd() {
  seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const int = (a, b) => a + Math.floor(rnd() * (b - a + 1));
const between = (a, b) => a + rnd() * (b - a);
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
const chance = (p) => rnd() < p;
const shuffle = (arr) => { const a = [...arr]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
const cents = (n) => Math.round(n * 100) / 100;
const sum = (arr, f = (x) => x) => cents(arr.reduce((s, x) => s + f(x), 0));
let idCounter = 0;
const uid = () => {
  const h = createHash('sha1').update(`duesdesk-demo-${++idCounter}`).digest('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
};
const hex = (n) => createHash('sha1').update(`h-${n}-${++idCounter}`).digest('hex').slice(0, 16);

const ms = (s) => Date.parse(`${s}T00:00:00Z`);
const iso = (t) => new Date(t).toISOString().slice(0, 10);
const addDays = (s, n) => iso(ms(s) + n * 86400000);
const addMonths = (s, n) => {
  const d = new Date(ms(s)); const day = d.getUTCDate();
  d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() + n);
  const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  d.setUTCDate(Math.min(day, last));
  return iso(d.getTime());
};
const daysBetween = (a, b) => Math.round((ms(b) - ms(a)) / 86400000);
const minDate = (a, b) => (a < b ? a : b);
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const money = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const normAlias = (s) => s.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^a-z0-9]/g, '');
/** A random day in [from, to] (clamped to today); null when the range is empty. */
const dayIn = (from, to) => { const end = minDate(to, TODAY); return from > end ? null : addDays(from, int(0, daysBetween(from, end))); };

// ---------- terms ----------
const TERMS = [
  { name: 'Fall 2024', start: '2024-08-26', end: '2024-12-20', amount: 900, perCapita: 215 },
  { name: 'Spring 2025', start: '2025-01-13', end: '2025-05-16', amount: 850, perCapita: 195 },
  { name: 'Fall 2025', start: '2025-08-25', end: '2025-12-19', amount: 950, perCapita: 215 },
  { name: 'Spring 2026', start: '2026-01-12', end: '2026-05-15', amount: 900, perCapita: 195 },
  { name: 'Fall 2026', start: '2026-08-24', end: '2026-12-18', amount: 975, perCapita: 215 }
];
const CURRENT = TERMS.length - 1;

// ---------- people ----------
const FIRST = ['Aiden', 'Brandon', 'Caleb', 'Dylan', 'Ethan', 'Felix', 'Gavin', 'Hunter', 'Isaac', 'Jaden', 'Kevin', 'Liam', 'Mateo', 'Nolan', 'Owen', 'Parker', 'Quentin', 'Riley', 'Sawyer', 'Tyler', 'Uriel', 'Victor', 'Wesley', 'Xavier', 'Zachary', 'Andre', 'Bryce', 'Carlos', 'Declan', 'Elias', 'Finn', 'Grant', 'Henry', 'Ian', 'Jonah', 'Kai', 'Luca', 'Miles', 'Nathan', 'Omar', 'Peter', 'Rohan', 'Spencer', 'Theo', 'Vincent', 'Wyatt', 'Yusuf', 'Abel', 'Brett', 'Cole', 'Damon', 'Evan', 'Fernando', 'Gabriel', 'Hudson', 'Ivan', 'Jasper', 'Keith', 'Landon', 'Marcus', 'Nico', 'Oscar', 'Pablo', 'Rhett', 'Santiago', 'Trevor'];
const LAST = ['Abbott', 'Barnes', 'Castillo', 'Dawson', 'Ellison', 'Foster', 'Garrett', 'Holloway', 'Ibarra', 'Jennings', 'Kowalski', 'Lindgren', 'Mercer', 'Nakamura', 'Okafor', 'Pruitt', 'Quinlan', 'Reyes', 'Sullivan', 'Thornton', 'Underwood', 'Vasquez', 'Whitaker', 'Yamamoto', 'Zimmerman', 'Alvarez', 'Bishop', 'Calderon', 'Donovan', 'Eastman', 'Fitzgerald', 'Gallagher', 'Hendricks', 'Iverson', 'Jacobs', 'Keller', 'Larsen', 'Mckenzie', 'Novak', 'Ortega', 'Patel', 'Rutledge', 'Schmidt', 'Tran', 'Vega', 'Walsh', 'Yoder', 'Ahmed', 'Bauer', 'Chavez', 'Dunn', 'Ellis', 'Flores', 'Greer', 'Hammond', 'Irving', 'Joyner', 'Kim', 'Lowry', 'Moreno', 'Nguyen', 'Osborne', 'Pierce', 'Ramsey', 'Sato', 'Tate'];
const PARENT_FIRST = ['Karen', 'Michael', 'Susan', 'David', 'Linda', 'Robert', 'Patricia', 'James', 'Jennifer', 'Mark', 'Angela', 'Steven'];

// Who was in the chapter when: the roster grows from 40 to 50, with 16 graduates moved to the archive.
const GROUPS = [
  { n: 24, first: 0, last: 4 },  // founding core, still active
  { n: 8, first: 0, last: 1 },   // graduated after Spring 2025
  { n: 8, first: 0, last: 3 },   // graduated after Spring 2026
  { n: 12, first: 2, last: 4 },  // Fall 2025 class
  { n: 14, first: 4, last: 4 }   // Fall 2026 class
];
const firstNames = shuffle(FIRST);
const lastNames = shuffle(LAST);
const people = [];
GROUPS.forEach((g) => {
  for (let i = 0; i < g.n; i++) {
    const idx = people.length;
    const first = firstNames[idx];
    const last = lastNames[idx];
    people.push({
      id: uid(), first, last, name: `${first} ${last}`,
      phone: `555-${int(200, 989)}-${int(1000, 9999)}`,
      email: `${first}.${last}`.toLowerCase() + '@example.edu',
      firstTerm: g.first, lastTerm: g.last, current: g.last === CURRENT,
      obligations: [], payments: [], aliases: [], lastTexted: 'Never', cleared: null
    });
  }
});
const activeInTerm = (p, t) => p.firstTerm <= t && t <= p.lastTerm;
const currentRoster = people.filter((p) => p.current);

// ---------- dues obligations and payments ----------
function slicesFor(amount, dues) {
  const n = dues.length;
  const part = cents(amount / n);
  return dues.map((due, i) => ({ id: uid(), due, amount: i === n - 1 ? cents(amount - part * (n - 1)) : part }));
}
function newObligation(p, kind, term, description, issued, amount, dues) {
  const ob = { id: uid(), kind, term, description, issued, amount, slices: slicesFor(amount, dues), payments: [], plan: dues.length > 1, tag: null };
  p.obligations.push(ob);
  return ob;
}
const addPayment = (ob, date, amount) => ob.payments.push({ date, amount: cents(amount) });

function planPastTerm(p, t) {
  const term = TERMS[t];
  const due = addDays(term.start, 35);
  const roll = rnd();
  if (roll < 0.26) { // payment plan
    const n = pick([3, 3, 4]);
    const dues = Array.from({ length: n }, (_, k) => addDays(due, 30 * k));
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, dues);
    ob.slices.forEach((s) => addPayment(ob, minDate(addDays(s.due, int(-6, 9)), '2026-10-20'), s.amount));
  } else if (roll < 0.40) { // pays late
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    addPayment(ob, addDays(due, int(5, 55)), term.amount);
  } else if (roll < 0.50) { // two halves
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    const half = cents(term.amount / 2);
    addPayment(ob, addDays(term.start, int(2, 25)), half);
    addPayment(ob, addDays(due, int(10, 45)), cents(term.amount - half));
  } else { // pays on time
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    addPayment(ob, addDays(term.start, int(1, 34)), term.amount);
  }
  if (chance(0.10)) { // an occasional fine
    const issued = addDays(term.start, int(25, 90));
    const amount = pick([25, 25, 35, 50]);
    const ob = newObligation(p, 'fine', term, pick(['Missed chapter fine', 'Late to formal fine', 'Missed mandatory event fine', 'House damage fine']), issued, amount, [addDays(issued, 14)]);
    addPayment(ob, addDays(issued, int(4, 30)), amount);
  }
}

// Fall 2026, the term underway on TODAY: a mix of paid, on-plan, behind and unpaid members.
const statusPlan = shuffle([
  ...Array(18).fill('paid-early'), ...Array(10).fill('paid-late'),
  ...Array(9).fill('plan-ok'), ...Array(2).fill('plan-behind'),
  ...Array(9).fill('delinquent'), ...Array(2).fill('vouched')
]);
function planCurrentTerm(p, mode) {
  const term = TERMS[CURRENT];
  const due = addDays(term.start, 35); // 2026-09-28
  p.mode = mode;
  if (mode === 'paid-early') {
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    addPayment(ob, addDays(term.start, int(3, 34)), term.amount);
  } else if (mode === 'paid-late') {
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    addPayment(ob, addDays(due, int(3, 22)), term.amount);
  } else if (mode === 'plan-ok' || mode === 'plan-behind') {
    const dues = ['2026-09-30', '2026-10-31', '2026-11-30', '2026-12-18'];
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, dues);
    const first = ob.slices[0];
    if (mode === 'plan-ok') {
      addPayment(ob, addDays(first.due, int(-4, 4)), first.amount);
      if (chance(0.3)) addPayment(ob, '2026-10-20', ob.slices[1].amount);
    } else {
      addPayment(ob, '2026-10-02', cents(first.amount * 0.4));
    }
  } else if (mode === 'delinquent') {
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    if (chance(0.4)) addPayment(ob, addDays('2026-09-15', int(0, 30)), cents(term.amount * between(0.25, 0.55)));
  } else if (mode === 'vouched') {
    const ob = newObligation(p, 'dues', term, `${term.name} Dues`, term.start, term.amount, [due]);
    addPayment(ob, addDays('2026-09-10', int(0, 25)), cents(term.amount * 0.4));
    p.cleared = term.end;
  }
}

people.forEach((p) => {
  for (let t = p.firstTerm; t <= p.lastTerm; t++) {
    if (t < CURRENT) planPastTerm(p, t);
  }
});
currentRoster.forEach((p, i) => planCurrentTerm(p, statusPlan[i]));

// ---------- payment records + reconciled bank/Venmo/GreekBill imports ----------
const flowEntries = [];
const counterpartyAliases = {};
const METHODS = [
  ['venmo', 0.42], ['zelle', 0.18], ['greekbill', 0.10], ['bank', 0.10], ['check', 0.14], ['cash', 0.06]
];
const SOURCE_BY_METHOD = { venmo: 'Venmo', zelle: 'Bank', bank: 'Bank', greekbill: 'GreekBill' };
function pickMethod() {
  let r = rnd();
  for (const [m, w] of METHODS) { if ((r -= w) < 0) return m; }
  return 'venmo';
}
function payerFor(p) {
  if (chance(0.3)) {
    const alias = `${pick(PARENT_FIRST)} ${p.last}`;
    if (!p.aliases.includes(alias)) p.aliases.push(alias);
    counterpartyAliases[normAlias(alias)] = p.id;
    return alias;
  }
  return p.name;
}
people.forEach((p) => {
  p.history = [];
  const all = p.obligations.flatMap((ob) => ob.payments.map((pay) => ({ ob, pay })))
    .sort((a, b) => a.pay.date.localeCompare(b.pay.date));
  all.forEach(({ ob, pay }, i) => {
    const method = pickMethod();
    const base = ob.kind === 'fine' ? `Fine: ${ob.description}` : `${ob.term.name} dues`;
    const planned = ob.plan ? ` (installment ${ob.payments.indexOf(pay) + 1} of ${ob.slices.length})` : '';
    if (SOURCE_BY_METHOD[method] && chance(0.85)) {
      const source = SOURCE_BY_METHOD[method];
      const payer = payerFor(p);
      const note = ob.kind === 'fine' ? 'fine' : pick(['dues', `${ob.term.name.split(' ')[0]} dues`, ob.term.name + ' dues', 'chapter dues']);
      const entryId = `${source === 'Venmo' ? 'venmo' : source === 'Bank' ? 'bank' : 'greekbill'}-${hex(i)}`;
      const description = source === 'Venmo' ? note : source === 'Bank' ? `${method === 'zelle' ? 'Zelle payment from' : 'Transfer from'} ${payer}` : `GreekBill payment - ${payer}`;
      const key = `flow-reconciliation:${entryId}`;
      flowEntries.push({
        id: entryId, date: pay.date, description, counterparty: payer, amount: pay.amount, source,
        reconciliationStatus: 'RECONCILED', reconciledMemberId: p.id, reconciledPaymentKey: key, category: null
      });
      p.history.push({ date: pay.date, amount: pay.amount, description: `Reconciled ${source}: ${description}`, idempotencyKey: key });
    } else {
      const label = method === 'check' ? `check #${int(1001, 4999)}` : method === 'cash' ? 'cash' : method === 'venmo' ? 'Venmo' : method === 'zelle' ? 'Zelle' : 'bank transfer';
      p.history.push({ date: pay.date, amount: pay.amount, description: `${base}${planned} - ${label}`, idempotencyKey: `demo-payment-${hex(i)}` });
    }
  });
});

// ---------- balances as of TODAY ----------
function allocate(ob, D) {
  let paid = sum(ob.payments.filter((x) => x.date <= D), (x) => x.amount);
  return ob.slices.map((s) => { const a = cents(Math.min(paid, s.amount)); paid = cents(paid - a); return { ...s, paid: a }; });
}
const paidBy = (ob, D) => sum(ob.payments.filter((x) => x.date <= D), (x) => x.amount);
const owedAt = (ob, D) => (ob.issued <= D ? cents(ob.amount - paidBy(ob, D)) : 0);
const pastDueAt = (ob, D) => (ob.issued <= D ? sum(allocate(ob, D).filter((s) => s.due < D), (s) => s.amount - s.paid) : 0);

function memberRecord(p) {
  const D = TODAY;
  const totalOwed = sum(p.obligations, (ob) => owedAt(ob, D));
  const pastDue = sum(p.obligations, (ob) => pastDueAt(ob, D));
  const paidThisMonth = sum(p.history.filter((h) => h.date.startsWith(D.slice(0, 7))), (h) => h.amount);
  const status = totalOwed <= 0.005 ? 'Paid in Full' : pastDue > 0.005 ? 'Delinquent' : 'Payment Plan';
  const charges = p.obligations.map((ob) => ({
    id: ob.id, date: ob.issued, description: ob.description, amount: ob.amount,
    pastDueAmount: pastDueAt(ob, D), dueDate: ob.slices[ob.slices.length - 1].due,
    paidAmount: paidBy(ob, D), lateFeeForChargeId: null
  }));
  const installments = p.obligations.filter((ob) => ob.plan).flatMap((ob) => allocate(ob, D).map((s, k) => ({
    id: s.id, dueDate: s.due, description: `${ob.term.name} installment ${k + 1} of ${ob.slices.length}`, amount: s.amount, paidAmount: s.paid
  })));
  p.state = { totalOwed, pastDue, paidThisMonth, status };
  return {
    id: p.id, name: p.name, phoneNumber: p.phone, email: p.email, totalOwed, pastDueBalance: pastDue, paidThisMonth, status,
    lastTextedDate: p.lastTexted, muteReminders: false,
    paymentHistory: [...p.history].sort((a, b) => a.date.localeCompare(b.date)),
    charges, installments, clearedUntilDate: p.cleared, credits: [], recognizedPayerAliases: p.aliases
  };
}

// ---------- reminder texts ----------
const smsLogs = [];
const tsText = (date, hh, mm) => {
  const h12 = ((hh + 11) % 12) + 1;
  return `${MONTHS[+date.slice(5, 7) - 1]} ${date.slice(8, 10)}, ${String(h12).padStart(2, '0')}:${String(mm).padStart(2, '0')} ${hh < 12 ? 'AM' : 'PM'}`;
};
function logText(p, date, owedTotal, pastDue, dueDate, termName) {
  const timestamp = tsText(date, 10, int(0, 12));
  smsLogs.push({
    timestamp, memberName: p.name, phoneNumber: p.phone, deliveryMode: 'SIMULATED', date,
    message: `Hey ${p.first}, friendly reminder from the Treasurer: $${money(pastDue)} is due on ${dueDate} (${termName} dues). Your current past-due balance is $${money(pastDue)} (total semester balance: $${money(owedTotal)}). Please reach out if you need help.`
  });
  p.lastTexted = timestamp;
}
// Weekly nudges to everyone currently past due, then a handful of reminders from each earlier term.
people.filter((p) => p.current).forEach((p) => {
  ['2026-10-06', '2026-10-13', '2026-10-20'].forEach((date) => {
    const pd = sum(p.obligations, (ob) => pastDueAt(ob, date));
    if (pd > 0.005 && !p.cleared) logText(p, date, sum(p.obligations, (ob) => owedAt(ob, date)), pd, '2026-09-28', 'Fall 2026');
  });
});
for (let t = 0; t < CURRENT; t++) {
  const due = addDays(TERMS[t].start, 35);
  const late = shuffle(people.filter((p) => activeInTerm(p, t) && p.obligations.some((ob) => ob.term === TERMS[t] && ob.kind === 'dues' && ob.payments.some((x) => x.date > addDays(due, 4))))).slice(0, 7);
  late.forEach((p) => {
    const date = addDays(due, int(3, 8));
    const ob = p.obligations.find((o) => o.term === TERMS[t] && o.kind === 'dues');
    logText(p, date, owedAt(ob, date), pastDueAt(ob, date) || owedAt(ob, date), due, TERMS[t].name);
  });
}
smsLogs.sort((a, b) => a.date.localeCompare(b.date));

const memberRecords = currentRoster.map(memberRecord);
const archivedRecords = people.filter((p) => !p.current).map(memberRecord).map((m) => ({ ...m, lastTextedDate: 'Never' }));

// ---------- residents (the chapter house) ----------
const houseMembers = shuffle(currentRoster.filter((p) => p.firstTerm <= 2)).slice(0, 10);
const houseRoster = [
  ...houseMembers.map((p) => ({ name: p.name, phone: p.phone, email: p.email, linked: p.id })),
  { name: 'Graham Pettigrew', phone: '555-318-4402', email: 'graham.pettigrew@example.edu', linked: '' },
  { name: 'Daniel Okonkwo', phone: '555-407-1186', email: 'daniel.okonkwo@example.edu', linked: '' }
];
const RENTS = [625, 650, 675, 695, 725, 750, 795];
const unpaid = new Set([2, 6, 9]);
const residents = houseRoster.map((r, i) => {
  const monthlyRent = pick(RENTS);
  const charges = [];
  let balanceDue = 0;
  if (unpaid.has(i)) {
    balanceDue = monthlyRent;
    if (i === 6) {
      charges.push({ id: uid(), date: '2026-10-01', description: 'Parking pass - October', amount: 40, pastDueAmount: 40, dueDate: '2026-10-05', paidAmount: 0, lateFeeForChargeId: null });
      balanceDue = cents(balanceDue + 40);
    }
  }
  return {
    id: uid(), name: r.name, phoneNumber: r.phone, room: String(101 + Math.floor(i / 4) * 100 + (i % 4)), monthlyRent,
    balanceDue, paidThisMonth: unpaid.has(i) ? 0 : monthlyRent, lastTextedDate: unpaid.has(i) ? tsText('2026-10-20', 10, 4) : 'Never',
    muteReminders: false, charges, linkedMemberId: r.linked, email: r.email
  };
});
const rentTotal = sum(residents, (r) => r.monthlyRent);
residents.filter((r) => r.balanceDue > 0).forEach((r) => {
  smsLogs.push({ timestamp: tsText('2026-10-20', 10, 4), memberName: r.name, phoneNumber: r.phoneNumber, deliveryMode: 'SIMULATED', date: '2026-10-20',
    message: `Hey ${r.name.split(' ')[0]}, quick reminder from the house: you have $${money(r.balanceDue)} of rent outstanding for ${r.room}. Please get it paid soon. Thanks!` });
});
smsLogs.sort((a, b) => a.date.localeCompare(b.date));

// ---------- recurring bills (each settlement leaves its own cash flow entry) ----------
const recurringBills = [];
function makeBill(def, firstDue, count, paidOffset) {
  const bill = { id: uid(), endDate: '', createdDate: HISTORY_START, settlements: [], reminderDays: [7, 3, 1], ...def };
  recurringBills.push(bill);
  let due = firstDue;
  for (let n = 0; due <= TODAY && n < count; n++) {
    const paidOn = def.paymentMode === 'AUTOMATIC' ? due : minDate(addDays(due, paidOffset()), TODAY);
    const entryId = `bill-${bill.id}-${due}`;
    flowEntries.push({
      id: entryId, date: paidOn, description: bill.name, counterparty: bill.payee, amount: -bill.amount,
      source: def.paymentMode === 'AUTOMATIC' ? 'Recurring bill (automatic)' : 'Recurring bill',
      reconciliationStatus: 'MANUAL', reconciledMemberId: null, reconciledPaymentKey: null, category: bill.category
    });
    bill.settlements.push({ dueDate: due, status: 'PAID', paidDate: paidOn, amount: bill.amount, automatic: def.paymentMode === 'AUTOMATIC', flowEntryId: entryId });
    due = addMonths(firstDue, (n + 1) * (def.frequency === 'YEARLY' ? 12 : 1));
  }
}
makeBill({ name: 'House lease - Greek Row', amount: 6400, frequency: 'MONTHLY', startDate: '2024-08-01', category: 'Venue & rentals', payee: 'Greek Row Properties', paymentMode: 'MANUAL' }, '2024-08-01', 99, () => int(0, 3));
makeBill({ name: 'Chapter insurance premium', amount: 4200, frequency: 'YEARLY', startDate: '2024-09-01', category: 'Insurance', payee: 'Fraternal Risk Mutual', paymentMode: 'MANUAL', reminderDays: [30, 14, 7] }, '2024-09-01', 9, () => int(0, 4));
makeBill({ name: 'Google Workspace', amount: 39, frequency: 'MONTHLY', startDate: '2024-08-05', category: 'Software & subscriptions', payee: 'Google', paymentMode: 'AUTOMATIC', reminderDays: [1] }, '2024-08-05', 99, () => 0);
makeBill({ name: 'Chapter website domain', amount: 24, frequency: 'YEARLY', startDate: '2025-03-15', category: 'Software & subscriptions', payee: 'Namecheap', paymentMode: 'AUTOMATIC', reminderDays: [7] }, '2025-03-15', 9, () => 0);

// ---------- everything else on the cash flow (manual entries) ----------
let manualN = 0;
function manual(date, direction, category, amount, description, party) {
  if (!date || date > TODAY || date < HISTORY_START) return;
  flowEntries.push({
    id: `manual-demo-${String(++manualN).padStart(4, '0')}`, date, description, counterparty: party,
    amount: cents(direction === 'in' ? amount : -amount), source: 'Manual entry',
    reconciliationStatus: 'MANUAL', reconciledMemberId: null, reconciledPaymentKey: null, category
  });
}
const BIZ = ['Rivertown Pizza', 'Campus Coffee Co.', 'Hartwell Auto Group', 'Main Street Barbers', 'Blue Heron Brewing', 'Summit Fitness', 'Oakline Dental'];
const VENDORS = ['Costco Business', "Sam's Club", 'Kroger', 'Pizza Corner'];
const EVENTS = ['Welcome back mixer', 'Date party', 'Brotherhood retreat', 'Semi-formal', 'Tailgate', 'Mocktail social', 'Spring fling', 'Parent weekend brunch', 'Sisterhood exchange'];

for (let mo = HISTORY_START; mo <= TODAY; mo = addMonths(mo, 1)) {
  const ym = mo.slice(0, 7);
  const day = (d) => `${ym}-${String(d).padStart(2, '0')}`;
  manual(day(12), 'out', 'Utilities', between(380, 640), 'Electric, water & gas', 'City Utilities');
  manual(day(18), 'out', 'Utilities', 89, 'Internet service', 'Metro Fiber');
  manual(day(28), 'out', 'Bank & payment fees', between(11, 22), 'Instant-transfer and card fees', 'Chapter bank');
  manual(day(28), 'in', 'Interest & bank income', between(2, 8), 'Savings interest', 'Chapter bank');
  const label = `${MONTH_NAMES[+ym.slice(5) - 1]} ${ym.slice(0, 4)}`;
  if (ym === TODAY.slice(0, 7)) manual(day(5), 'in', 'Housing & rent income', sum(residents, (r) => r.paidThisMonth), `House rent - ${label}`, 'House residents');
  else manual(day(5), 'in', 'Housing & rent income', rentTotal - (chance(0.25) ? pick(RENTS) : 0), `House rent - ${label}`, 'House residents');
}
manual('2025-07-18', 'out', 'Travel & conferences', between(2400, 2900), 'National convention - delegates', 'Convention Travel');
manual('2026-07-17', 'out', 'Travel & conferences', between(2400, 2900), 'National convention - delegates', 'Convention Travel');

TERMS.forEach((term, t) => {
  const s = term.start, e = term.end;
  const fall = t % 2 === 0;
  const count = people.filter((p) => activeInTerm(p, t)).length;
  const added = people.filter((p) => p.firstTerm === t && t >= 2).length;
  manual(addDays(s, 21), 'out', 'National / headquarters fees', count * term.perCapita, `${term.name} per-capita fees (${count} members)`, 'National Headquarters');
  if (added) {
    manual(addDays(s, 28), 'in', 'Initiation & new member fees', added * 185, `${term.name} new member initiation (${added})`, 'New member class');
    manual(addDays(s, 30), 'out', 'National / headquarters fees', added * 120, 'Initiation fees remitted', 'National Headquarters');
  }
  manual(addDays(s, -14), 'out', 'Recruitment', between(1800, 3200), 'Recruitment week supplies & food', 'Costco Business');
  manual(addDays(s, -12), 'out', 'Marketing & printing', between(150, 400), 'Recruitment flyers & banners', 'CopyCat Print');
  for (let k = 0, n = int(3, 5); k < n; k++) {
    const date = dayIn(addDays(s, 14), addDays(e, -10));
    const name = pick(EVENTS);
    const cost = between(700, 2600);
    manual(date, 'out', 'Events & socials', cost, name, pick(['Campus DJ Collective', 'Rivertown Event Hall', 'Lakeside Venue Co.']));
    if (date && chance(0.5)) manual(addDays(date, 1), 'in', 'Event & ticket sales', cost * between(0.4, 0.7), `${name} ticket sales`, 'Guests & members');
  }
  manual(addDays(e, -25), 'out', 'Venue & rentals', between(1800, 3200), `${fall ? 'Fall' : 'Spring'} formal venue & catering deposit`, 'Grand Ballroom');
  manual(addDays(e, -24), 'in', 'Event & ticket sales', between(2000, 3400), `${fall ? 'Fall' : 'Spring'} formal ticket sales`, 'Guests & members');
  for (let k = 0, n = int(4, 6); k < n; k++) manual(dayIn(addDays(s, 7), addDays(e, -7)), 'out', 'Food & beverages', between(220, 680), 'Weekly chapter dinner supplies', pick(VENDORS));
  for (let k = 0; k < 2; k++) manual(dayIn(addDays(s, 10), addDays(e, -10)), 'out', 'Supplies & equipment', between(120, 560), pick(['Folding tables', 'Speaker rental', 'Cleaning supplies', 'Whiteboard & markers']), 'Office Depot');
  const phil = dayIn(addDays(s, 40), addDays(s, 80));
  const philAmount = between(1800, 4200);
  manual(phil, 'in', 'Fundraising', philAmount, `${term.name} philanthropy event`, 'Community donors & tickets');
  if (phil) manual(addDays(phil, 14), 'out', 'Philanthropy & charitable giving', cents(philAmount * 0.85), "Donation to Children's Hospital Foundation", "Children's Hospital Foundation");
  for (let k = 0; k < 2; k++) manual(dayIn(addDays(s, 15), addDays(e, -15)), 'in', 'Fundraising', between(400, 1200), pick(['Car wash', 'Restaurant night', 'Snack stand at game day']), pick(BIZ));
  for (let k = 0; k < 2; k++) { const biz = pick(BIZ); manual(dayIn(addDays(s, 5), addDays(e, -20)), 'in', 'Sponsorships', between(500, 1500), `Local business sponsorship - ${biz}`, biz); }
  const merch = dayIn(addDays(s, 20), addDays(e, -15));
  manual(merch && addDays(merch, -14), 'out', 'Marketing & printing', between(400, 900), 'Chapter apparel order', 'Greek Threads Co.');
  manual(merch, 'in', 'Merchandise sales', between(600, 1800), 'Chapter apparel sales', 'Members & alumni');
  for (let k = 0, n = int(1, 2); k < n; k++) manual(dayIn(addDays(s, 10), addDays(e, -10)), 'out', 'House maintenance & repairs', between(250, 1400), pick(['Plumbing repair', 'HVAC filter & service', 'Roof leak patch', 'Door lock replacement', 'Pest control']), pick(['Fix-It Brothers', 'AirFlow HVAC', 'Pro Pest Control']));
  manual(addDays(e, -5), 'out', 'Awards & gifts', between(250, 500), 'End-of-term awards banquet gifts', 'Trophy Depot');
  manual(fall ? `${s.slice(0, 4)}-11-${int(18, 26)}` : `${s.slice(0, 4)}-04-${int(10, 24)}`, 'in', 'Donations & gifts', fall ? between(1500, 4000) : between(800, 2000), fall ? 'Alumni giving day' : 'Spring alumni appeal', 'Alumni association');
  for (let k = 0; k < 3; k++) manual(dayIn(addDays(s, 5), addDays(e, 15)), 'in', 'Donations & gifts', between(100, 600), `Alumni gift - ${pick(FIRST)} ${pick(LAST)}`, 'Alumni');
  manual(dayIn(addDays(s, 20), addDays(e, 5)), 'in', 'Reimbursements received', between(80, 300), 'Rebate on deposit', 'National Headquarters');
  manual(dayIn(addDays(s, 5), e), 'out', 'Other expense', between(80, 400), pick(['Postage & mailing', 'Notary & document fees', 'Photo prints']), 'Office Depot');
  if (!fall) manual(addDays(s, 10), 'out', 'Travel & conferences', between(900, 1600), 'Regional leadership conference registration & travel', 'Conference Travel');
  if (t === 1) manual('2025-02-20', 'out', 'Refunds issued', 450, 'Dues refund - member withdrew mid-term', 'Former member');
  if (t === 3) manual('2026-03-12', 'out', 'Refunds issued', 200, 'Overpayment refund', 'Member parent');
});

// Deposits waiting to be matched on TODAY (these are what the reconciliation screen shows).
const delinquents = currentRoster.filter((p) => p.mode === 'delinquent' && p.state.totalOwed > 0);
const behind = currentRoster.find((p) => p.mode === 'plan-behind');
const pending = [
  { who: delinquents[0], source: 'Venmo', date: '2026-10-24', amount: 300, note: 'dues part 1' },
  { who: delinquents[1], source: 'Bank', date: '2026-10-23', amount: 500, note: `Zelle payment from ${delinquents[1].name}` },
  { who: behind, source: 'Venmo', date: '2026-10-23', amount: cents(TERMS[CURRENT].amount / 4 * 0.6), note: 'dues', payer: `${pick(PARENT_FIRST)} ${behind.last}` },
  { who: delinquents[2], source: 'Bank', date: '2026-10-22', amount: 425, note: 'Transfer from joint account', payer: `${pick(PARENT_FIRST)} ${delinquents[2].last}` },
  { who: null, source: 'Venmo', date: '2026-10-21', amount: 80, note: 'tailgate tix', payer: 'Casey Brandt' }
];
pending.forEach((x, i) => flowEntries.push({
  id: `${x.source === 'Venmo' ? 'venmo' : 'bank'}-${hex(900 + i)}`, date: x.date, description: x.note, counterparty: x.payer ?? x.who.name,
  amount: x.amount, source: x.source, reconciliationStatus: 'PENDING', reconciledMemberId: null, reconciledPaymentKey: null, category: null
}));
flowEntries.sort((a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id));

// ---------- cash balance: pick an opening balance that keeps the account positive ----------
const rows = [
  ...people.flatMap((p) => p.history.map((h) => ({ date: h.date, amount: h.amount }))),
  ...flowEntries.filter((f) => f.reconciliationStatus !== 'RECONCILED').map((f) => ({ date: f.date, amount: f.amount }))
].sort((a, b) => a.date.localeCompare(b.date));
let running = 0, minRunning = 0;
rows.forEach((r) => { running += r.amount; minRunning = Math.min(minRunning, running); });
const openingCash = Math.ceil((5000 - minRunning) / 100) * 100;

// ---------- chair budgets, current term plus four closed terms ----------
const CHAIRS = [
  { name: 'Social', cap: 12000, goal: 0, color: '#2563EB', items: ['DJ & sound', 'Venue deposit', 'Decorations', 'Catering', 'Security staff', 'Photo booth'] },
  { name: 'Recruitment', cap: 8000, goal: 2500, color: '#7C3AED', items: ['Rush shirts', 'Banquet food', 'Signage', 'Giveaways', 'Campus table fee'] },
  { name: 'Philanthropy', cap: 6000, goal: 8000, color: '#047857', items: ['Event supplies', 'Promotion & printing', 'Tournament entry kits', 'Food truck deposit'] },
  { name: 'Brotherhood', cap: 4500, goal: 0, color: '#C2410C', items: ['Retreat cabin', 'Group dinner', 'Intramural fees', 'Bowling night'] },
  { name: 'House Manager', cap: 7500, goal: 0, color: '#0E7490', items: ['Cleaning supplies', 'Furniture repair', 'Appliance replacement', 'Paint & hardware'] },
  { name: 'Risk & Scholarship', cap: 3000, goal: 0, color: '#BE185D', items: ['Safety training', 'Study room snacks', 'Tutoring stipend', 'Scholarship plaque'] }
];
const RAISE = ['Car wash', 'Restaurant night', 'Alumni donation', 'Raffle tickets', 'Corporate match'];
function split(total, n) {
  const w = Array.from({ length: n }, () => 0.5 + rnd());
  const s = w.reduce((a, b) => a + b, 0);
  const parts = w.map((x) => cents(total * x / s));
  parts[n - 1] = cents(parts[n - 1] + total - parts.reduce((a, b) => a + b, 0));
  return parts;
}
function chairTerm(chair, term, current) {
  const spendTarget = cents(chair.cap * (current ? between(0.30, 0.55) : between(0.58, 0.93)));
  const items = split(spendTarget, int(4, 7)).map((amount) => ({
    id: uid(), date: dayIn(term.start, current ? TODAY : term.end), description: pick(chair.items), amount, receiptFileName: null, creditedMemberId: null
  }));
  const raised = chair.goal > 0
    ? split(cents(chair.goal * (current ? between(0.25, 0.6) : between(0.7, 1.1))), int(3, 4)).map((amount) => ({ id: uid(), date: dayIn(term.start, current ? TODAY : term.end), description: pick(RAISE), amount }))
    : [];
  items.sort((a, b) => a.date.localeCompare(b.date));
  raised.sort((a, b) => a.date.localeCompare(b.date));
  return { items, raised };
}
const chairBudgets = [];
const chairBudgetHistory = [];
CHAIRS.forEach((chair, i) => {
  const id = uid();
  const holder = currentRoster[(i * 7) % currentRoster.length];
  const now = chairTerm(chair, TERMS[CURRENT], true);
  chairBudgets.push({ id, chairName: chair.name, chairMemberId: holder.id, budgetCap: chair.cap, raiseGoal: chair.goal, accentColor: chair.color, reimbursements: now.items, raised: now.raised });
  for (let t = 0; t < CURRENT; t++) {
    const past = chairTerm(chair, TERMS[t], false);
    chairBudgetHistory.push({
      budgetId: id, chairName: chair.name, termName: TERMS[t].name, endedOn: TERMS[t].end, budgetCap: chair.cap, raiseGoal: chair.goal,
      spent: sum(past.items, (x) => x.amount), raisedTotal: sum(past.raised, (x) => x.amount), reimbursements: past.items, raised: past.raised
    });
  }
});

// ---------- weekly metric history (what the dashboard trend charts plot) ----------
const metricHistory = [];
for (let d = '2024-08-25'; d <= TODAY; d = addDays(d, 7)) {
  const active = people.filter((p) => addDays(TERMS[p.firstTerm].start, -14) <= d && d <= addDays(TERMS[p.lastTerm].end, 30));
  if (!active.length) continue;
  const recorded = sum(active, (p) => sum(p.history.filter((h) => h.date <= d), (h) => h.amount));
  const owed = sum(active, (p) => sum(p.obligations, (ob) => owedAt(ob, d)));
  const pastDue = sum(active, (p) => sum(p.obligations, (ob) => pastDueAt(ob, d)));
  const paidMonth = sum(active, (p) => sum(p.history.filter((h) => h.date <= d && h.date.startsWith(d.slice(0, 7))), (h) => h.amount));
  const pool = recorded + owed;
  metricHistory.push({
    date: d,
    values: {
      'collection-velocity-run-rate': cents(paidMonth / +d.slice(8, 10)),
      'net-collection-rate': cents(pool > 0 ? recorded / pool * 100 : 0),
      'delinquency-default-rate': cents(pool > 0 ? pastDue / pool * 100 : 0),
      'average-dues-per-member-adpm': cents(recorded / active.length)
    }
  });
}

// ---------- settings + final file ----------
const settings = {
  smsTemplate: 'Hey {name}, friendly reminder from the Treasurer: ${dueAmount} is due on {dueDate} ({reason}). Your current past-due balance is ${pastDue} (total semester balance: ${totalOwed}). Pay here: {{pay_links}}',
  autoSendEnabled: false, frequency: 'WEEKLY', dayOfWeek: 'MONDAY', sendTime: '10:00', lastAutoRunDate: '',
  reminderDaysBeforeDue: 3, reminderDaysAfterDue: 1,
  ihqBillAmount: cents(currentRoster.length * TERMS[CURRENT].perCapita), ihqStartDate: TERMS[CURRENT].start,
  appearanceMode: 'light', attentionSnoozes: {}, cloudBackupEnabled: false, cloudBackupFolder: '', cloudBackupRetentionDays: 30,
  lateFeesEnabled: false, lateFeeGraceDays: 7, lateFeeType: 'FIXED', lateFeeAmount: 25,
  socialClearanceTolerance: 50, socialClearanceDuesOnly: true, openingCashBalance: openingCash, upcomingBills: '',
  chapterCashServiceHandles: [
    { service: 'VENMO', handle: '@gammaomega-treasurer' }, { service: 'CASH_APP', handle: '$GammaOmegaDues' },
    { service: 'PAYPAL', handle: 'gammaomegatreasurer' }, { service: 'ZELLE', handle: 'treasurer@gammaomega.example' }
  ],
  chapterName: 'Gamma Omega Chapter (Demo)', currentTermName: TERMS[CURRENT].name, lastRolloverDate: TERMS[CURRENT].start,
  savedChargePresets: [
    { id: uid(), name: 'Missed chapter - $25 fine', amount: 25, type: 'FINE' },
    { id: uid(), name: 'Late to formal - $35 fine', amount: 35, type: 'FINE' },
    { id: uid(), name: 'Scholarship discount - $100', amount: 100, type: 'DISCOUNT' }
  ],
  dashboardStats: ['inflow', 'balance', 'pastdue', 'rate', 'ihq']
};
const data = {
  members: memberRecords, settings, smsLogs, residents, flowEntries, metricHistory, counterpartyAliases,
  chairBudgets, presaleCampaigns: [], recurringBills, archivedMembers: archivedRecords, chairBudgetHistory,
  receiptFiles: {}, smsUsageDate: '', smsUsageCount: 0
};

// ---------- sanity checks ----------
const fail = (msg) => { throw new Error(`Demo data check failed: ${msg}`); };
if (memberRecords.length !== 50) fail(`expected 50 members, got ${memberRecords.length}`);
memberRecords.forEach((m) => {
  if (m.pastDueBalance > m.totalOwed + 0.005) fail(`${m.name} past due exceeds balance`);
  if (m.totalOwed < 0) fail(`${m.name} has a negative balance`);
});
archivedRecords.forEach((m) => { if (m.totalOwed > 0.005) fail(`${m.name} graduated owing money`); });
const keys = new Set();
[...memberRecords, ...archivedRecords].forEach((m) => m.paymentHistory.forEach((h) => { if (keys.has(h.idempotencyKey)) fail('duplicate payment key'); keys.add(h.idempotencyKey); }));
const ids = new Set();
flowEntries.forEach((f) => { if (ids.has(f.id)) fail(`duplicate flow entry ${f.id}`); ids.add(f.id); });
flowEntries.filter((f) => f.reconciliationStatus === 'RECONCILED').forEach((f) => { if (!keys.has(f.reconciledPaymentKey)) fail('reconciled entry has no payment'); });
if (rows[0].date < HISTORY_START || rows[rows.length - 1].date > TODAY) fail('history outside its window');

writeFileSync(OUT, JSON.stringify(data, null, 2));

// ---------- summary ----------
const counts = (f) => memberRecords.filter(f).length;
const inflow = sum(rows.filter((r) => r.amount > 0), (r) => r.amount);
const outflow = sum(rows.filter((r) => r.amount < 0), (r) => -r.amount);
console.log(`Wrote ${OUT}`);
console.log(`  members: ${memberRecords.length} current, ${archivedRecords.length} graduated   terms: ${TERMS.map((t) => t.name).join(', ')}`);
console.log(`  status: ${counts((m) => m.status === 'Paid in Full')} paid in full, ${counts((m) => m.status === 'Payment Plan')} payment plan, ${counts((m) => m.status === 'Delinquent')} delinquent`);
console.log(`  balance: $${money(sum(memberRecords, (m) => m.totalOwed))} owed, $${money(sum(memberRecords, (m) => m.pastDueBalance))} past due, $${money(sum(memberRecords, (m) => m.paidThisMonth))} paid this month`);
console.log(`  payments: ${keys.size}   cash flow rows: ${rows.length} (in $${money(inflow)}, out $${money(outflow)}), opening cash $${money(openingCash)}, min balance $${money(openingCash + minRunning)}`);
console.log(`  pending deposits: ${pending.length}   reminders: ${smsLogs.length}   bills: ${recurringBills.length}   chair terms: ${chairBudgetHistory.length}   metric points: ${metricHistory.length}`);
