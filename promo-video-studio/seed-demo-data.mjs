#!/usr/bin/env node
/**
 * Generates a realistic demo store.json with 72 members, populated features, and sample data
 * for all 8 promo video scenes. NEVER touches real user data.
 */
import { writeFileSync } from 'fs';
import { randomUUID } from 'crypto';

const FIRST_NAMES = ['Alex','Blake','Cameron','Dakota','Ellis','Finley','Gray','Harper','Indi','Jordan','Kelly','Lane','Mason','Noah','Parker','Quinn','Riley','Sam','Taylor','Wyatt','Avery','Bailey','Casey','Drew','Emerson','Frankie','Hayden','Jamie','Kai','Morgan','Phoenix','River','Sage','Skyler','Tanner','Adrian','Blake','Carter','Devin','Eli','Finn','Gabe','Hunter','Isaiah','Jack','Kyle','Logan','Max','Nathan','Owen','Preston'];
const LAST_NAMES = ['Anderson','Brooks','Carter','Davis','Evans','Foster','Gray','Harris','Irwin','Jones','King','Lee','Miller','Nelson','Owen','Parker','Quinn','Ross','Smith','Turner','Underwood','Vincent','Walker','Young','Zhang','Abbott','Bennett','Collins','Duncan','Ellis','Fisher','Grant','Hayes','Jensen','Knight','Lambert','Mason','Nash','Perry','Reed','Stone','Wells'];

function randomName() {
  const first = FIRST_NAMES[Math.floor(Math.random() * FIRST_NAMES.length)];
  const last = LAST_NAMES[Math.floor(Math.random() * LAST_NAMES.length)];
  return `${first} ${last}`;
}

function randomPhone() {
  return `555-${String(Math.floor(Math.random() * 900) + 100)}-${String(Math.floor(Math.random() * 9000) + 1000)}`;
}

function randomEmail(name) {
  const clean = name.toLowerCase().replace(/\s+/g, '.');
  return `${clean}@example.edu`;
}

function generateMembers() {
  const members = [];
  const statuses = [
    { status: 'Paid in Full', count: 43, owedRange: [0, 0], pastDueRange: [0, 0] },
    { status: 'Payment Plan', count: 15, owedRange: [200, 850], pastDueRange: [0, 0] },
    { status: 'Delinquent', count: 12, owedRange: [150, 950], pastDueRange: [75, 450] },
    { status: 'Manual Vouch', count: 2, owedRange: [300, 700], pastDueRange: [0, 0] }
  ];

  statuses.forEach(({ status, count, owedRange, pastDueRange }) => {
    for (let i = 0; i < count; i++) {
      const name = randomName();
      const owed = Math.round((Math.random() * (owedRange[1] - owedRange[0]) + owedRange[0]) * 100) / 100;
      const pastDue = Math.round((Math.random() * (pastDueRange[1] - pastDueRange[0]) + pastDueRange[0]) * 100) / 100;
      
      const member = {
        id: randomUUID(),
        name,
        phoneNumber: randomPhone(),
        email: randomEmail(name),
        totalOwed: owed,
        pastDueBalance: pastDue,
        paidThisMonth: status === 'Paid in Full' ? Math.round(Math.random() * 900 * 100) / 100 : 0,
        status,
        lastTextedDate: 'Never',
        muteReminders: false,
        paymentHistory: [],
        charges: [],
        installments: [],
        credits: [],
        recognizedPayerAliases: []
      };

      // Add payment history for paid members
      if (status === 'Paid in Full') {
        member.paymentHistory.push({
          date: '2024-09-15',
          amount: 900.00,
          description: 'Fall 2024 Dues',
          idempotencyKey: `payment-${randomUUID()}`
        });
      }

      // Add charges for those with balances
      if (owed > 0) {
        member.charges.push({
          id: randomUUID(),
          date: '2024-09-01',
          description: 'Fall 2024 Dues',
          amount: 900.00,
          paidAmount: 900.00 - owed,
          pastDueAmount: pastDue,
          dueDate: '2024-09-30'
        });
      }

      // Add installments for payment plans
      if (status === 'Payment Plan') {
        const installment1 = { id: randomUUID(), description: 'Installment 1 of 3', amount: 300.00, paidAmount: 300.00, dueDate: '2024-09-30' };
        const installment2 = { id: randomUUID(), description: 'Installment 2 of 3', amount: 300.00, paidAmount: Math.random() > 0.5 ? 300.00 : 0, dueDate: '2024-10-31' };
        const installment3 = { id: randomUUID(), description: 'Installment 3 of 3', amount: 300.00, paidAmount: 0, dueDate: '2024-11-30' };
        member.installments = [installment1, installment2, installment3];
      }

      // Add manual vouch for 2 members
      if (status === 'Manual Vouch') {
        member.clearedUntilDate = '2024-12-15';
      }

      members.push(member);
    }
  });

  // Add special member "Conner Stover" for Scene 2 (reconciliation demo)
  members.push({
    id: randomUUID(),
    name: 'Conner Stover',
    phoneNumber: '555-123-9876',
    email: 'conner.stover@example.edu',
    totalOwed: 650.00,
    pastDueBalance: 0,
    paidThisMonth: 0,
    status: 'Delinquent',
    lastTextedDate: 'Never',
    muteReminders: false,
    paymentHistory: [],
    charges: [{
      id: randomUUID(),
      date: '2024-09-01',
      description: 'Fall 2024 Dues',
      amount: 900.00,
      paidAmount: 250.00,
      pastDueAmount: 0,
      dueDate: '2024-09-30'
    }],
    installments: [],
    credits: [],
    recognizedPayerAliases: []
  });

  return members;
}

function generateResidents() {
  const residents = [];
  const rooms = ['101', '102', '103', '104', '105', '106', '201', '202', '203', '204', '205', '206', '301', '302', '303', '304', '305', '306'];
  
  for (let i = 0; i < 18; i++) {
    residents.push({
      id: randomUUID(),
      name: randomName(),
      phoneNumber: randomPhone(),
      room: rooms[i],
      monthlyRent: 550 + Math.floor(Math.random() * 200),
      balanceDue: Math.random() > 0.7 ? Math.round(Math.random() * 500 * 100) / 100 : 0,
      paidThisMonth: 0,
      lastTextedDate: 'Never',
      muteReminders: false,
      linkedMemberId: null,
      email: '',
      charges: []
    });
  }
  
  return residents;
}

function generateChairBudgets() {
  const chairs = [
    { name: 'Social', cap: 12000, raiseGoal: 0, color: '#2563EB' },
    { name: 'Recruitment', cap: 8000, raiseGoal: 5000, color: '#7C3AED' },
    { name: 'Brotherhood', cap: 5000, raiseGoal: 0, color: '#047857' },
    { name: 'House', cap: 15000, raiseGoal: 0, color: '#C2410C' }
  ];

  return chairs.map(chair => ({
    id: randomUUID(),
    chairName: chair.name,
    budgetCap: chair.cap,
    raiseGoal: chair.raiseGoal,
    accentColor: chair.color,
    chairMemberId: null,
    reimbursements: [],
    raised: []
  }));
}

function generateFlowEntries() {
  const entries = [];
  
  // Add pending bank deposit from "Susan Walding" for Scene 2
  entries.push({
    id: `bank-${Date.now()}-susan-walding`,
    date: '2024-10-20',
    description: 'Deposit - Susan Walding',
    counterparty: 'Susan Walding',
    amount: 650.00,
    source: 'Bank CSV',
    category: 'Dues Payment',
    reconciliationStatus: 'PENDING',
    reconciledMemberId: null,
    reconciledPaymentKey: null
  });

  // Add some GreekBill and Venmo entries
  entries.push({
    id: `greekbill-${Date.now()}-1`,
    date: '2024-10-18',
    description: 'GreekBill Payment - John Doe',
    counterparty: 'John Doe',
    amount: 900.00,
    source: 'GreekBill CSV',
    category: 'Dues Payment',
    reconciliationStatus: 'PENDING',
    reconciledMemberId: null,
    reconciledPaymentKey: null
  });

  entries.push({
    id: `venmo-${Date.now()}-1`,
    date: '2024-10-19',
    description: 'Venmo Payment - @jsmith',
    counterparty: 'Jane Smith',
    amount: 450.00,
    source: 'Venmo CSV',
    category: 'Dues Payment',
    reconciliationStatus: 'PENDING',
    reconciledMemberId: null,
    reconciledPaymentKey: null
  });

  return entries;
}

function generateRecurringBills() {
  return [{
    id: randomUUID(),
    name: 'IHQ Semester Bill',
    amount: 14500.00,
    frequency: 'MONTHLY',
    startDate: '2024-09-01',
    endDate: '',
    category: 'Headquarters',
    payee: 'National Headquarters',
    paymentMode: 'MANUAL',
    reminderDays: [7, 3, 1],
    createdDate: '2024-09-01',
    settlements: []
  }];
}

const demoData = {
  members: generateMembers(),
  settings: {
    smsTemplate: 'Hey {name}, quick reminder: ${pastDue} past due. Pay via {{venmo_link}} or {{pay_links}}. Thanks!',
    chapterName: 'Alpha Beta Chapter',
    currentTermName: 'Fall 2024',
    lastRolloverDate: '2024-08-25',
    socialClearanceTolerance: 50.00,
    socialClearanceDuesOnly: false,
    ihqBillAmount: 14500.00,
    ihqStartDate: '2024-09-01',
    chapterCashServiceHandles: [
      { service: 'VENMO', handle: '@alphabeta-treasurer' },
      { service: 'CASH_APP', handle: '$AlphaBetaDues' },
      { service: 'PAYPAL', handle: 'alphabetatreas' },
      { service: 'ZELLE', handle: 'treasurer@alphabeta.org' }
    ],
    appearanceMode: 'light',
    cloudBackupEnabled: true,
    cloudBackupFolder: '/Users/treasurer/Library/Mobile Documents/com~apple~CloudDocs/DuesDesk Backups',
    dashboardStats: ['inflow', 'balance', 'pastdue', 'rate', 'ihq'],
    savedChargePresets: []
  },
  smsLogs: [],
  residents: generateResidents(),
  flowEntries: generateFlowEntries(),
  // The keys must match the four stats the Numbers page can chart, or the history graphs stay empty.
  metricHistory: [
    { date: '2024-09-15', values: { 'collection-velocity-run-rate': 1290.00, 'net-collection-rate': 72.8, 'delinquency-default-rate': 5.2, 'average-dues-per-member-adpm': 530.14 } },
    { date: '2024-09-30', values: { 'collection-velocity-run-rate': 1403.33, 'net-collection-rate': 77.9, 'delinquency-default-rate': 4.4, 'average-dues-per-member-adpm': 576.71 } },
    { date: '2024-10-15', values: { 'collection-velocity-run-rate': 3216.67, 'net-collection-rate': 84.6, 'delinquency-default-rate': 3.2, 'average-dues-per-member-adpm': 660.96 } },
    { date: '2024-10-20', values: { 'collection-velocity-run-rate': 2412.50, 'net-collection-rate': 84.6, 'delinquency-default-rate': 3.2, 'average-dues-per-member-adpm': 660.96 } }
  ],
  counterpartyAliases: {},
  chairBudgets: generateChairBudgets(),
  presaleCampaigns: [],
  recurringBills: generateRecurringBills(),
  archivedMembers: [],
  chairBudgetHistory: [],
  receiptFiles: {},
  smsUsageDate: '',
  smsUsageCount: 0
};

// Calculate actual stats
const totalCollected = demoData.members.reduce((sum, m) => sum + (900 - m.totalOwed), 0);
const totalOutstanding = demoData.members.reduce((sum, m) => sum + m.totalOwed, 0);
const totalPastDue = demoData.members.reduce((sum, m) => sum + m.pastDueBalance, 0);
const collectionRate = ((totalCollected / (totalCollected + totalOutstanding)) * 100).toFixed(1);

console.log('✅ Generated demo data:');
console.log(`   - ${demoData.members.length} members`);
console.log(`   - $${totalCollected.toFixed(2)} collected`);
console.log(`   - $${totalOutstanding.toFixed(2)} outstanding`);
console.log(`   - $${totalPastDue.toFixed(2)} past due`);
console.log(`   - ${collectionRate}% collection rate`);
console.log(`   - ${demoData.residents.length} residents`);
console.log(`   - ${demoData.chairBudgets.length} chair budgets`);
console.log(`   - ${demoData.flowEntries.length} pending reconciliations`);

writeFileSync('./demo-store.json', JSON.stringify(demoData, null, 2));
console.log('\n📁 Saved to ./demo-store.json');
console.log('⚠️  SAFE: This file is in .gitignore and never touches real user data.');
