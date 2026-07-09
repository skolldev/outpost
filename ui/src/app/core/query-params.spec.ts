import { issueParams, logParams, traceParams } from './query-params';

describe('issueParams', () => {
  it('includes every filter when present', () => {
    expect(
      issueParams({
        project: 1,
        environment: ['prod', 'staging'],
        status: 'unresolved',
        query: 'boom',
        sort: 'count',
        from: '2026-01-01T00:00:00Z',
        cursor: 'cursor-1',
      }),
    ).toEqual({
      project: 1,
      environment: ['prod', 'staging'],
      status: 'unresolved',
      query: 'boom',
      sort: 'count',
      from: '2026-01-01T00:00:00Z',
      cursor: 'cursor-1',
    });
  });

  it('omits keys for absent filters', () => {
    expect(issueParams({})).toEqual({});
  });

  it('keeps project 0 (falsy but not nullish)', () => {
    expect(issueParams({ project: 0 })).toEqual({ project: 0 });
  });

  it('omits environment when the array is empty', () => {
    expect(issueParams({ environment: [] })).toEqual({});
  });

  it('omits falsy string filters', () => {
    expect(issueParams({ status: '', query: '', sort: '', from: '', cursor: '' })).toEqual({});
  });
});

describe('logParams', () => {
  it('includes every filter when present', () => {
    expect(
      logParams({
        project: 2,
        environment: ['prod'],
        level: ['error', 'fatal'],
        traceId: 'trace-abc',
        query: 'checkout',
        from: '2026-01-01T00:00:00Z',
        to: '2026-01-02T00:00:00Z',
        cursor: 'cursor-2',
      }),
    ).toEqual({
      project: 2,
      environment: ['prod'],
      level: ['error', 'fatal'],
      trace_id: 'trace-abc',
      query: 'checkout',
      from: '2026-01-01T00:00:00Z',
      to: '2026-01-02T00:00:00Z',
      cursor: 'cursor-2',
    });
  });

  it('omits keys for absent filters', () => {
    expect(logParams({})).toEqual({});
  });

  it('omits level and environment when the arrays are empty', () => {
    expect(logParams({ environment: [], level: [] })).toEqual({});
  });

  it('omits trace_id when traceId is falsy', () => {
    expect(logParams({ traceId: '' })).toEqual({});
  });
});

describe('traceParams', () => {
  it('includes every filter when present', () => {
    expect(
      traceParams({
        project: 1,
        environment: ['prod'],
        release: 'shop@1.0.0',
        query: '/checkout',
        minDuration: 100,
        maxDuration: 2000,
        hasErrors: true,
        from: '2026-01-01T00:00:00Z',
        to: '2026-01-02T00:00:00Z',
        cursor: 'cursor-3',
      }),
    ).toEqual({
      project: 1,
      environment: ['prod'],
      release: 'shop@1.0.0',
      query: '/checkout',
      min_duration: 100,
      max_duration: 2000,
      has_errors: 'true',
      from: '2026-01-01T00:00:00Z',
      to: '2026-01-02T00:00:00Z',
      cursor: 'cursor-3',
    });
  });

  it('omits keys for absent filters', () => {
    expect(traceParams({})).toEqual({});
  });

  it('keeps a minDuration/maxDuration of 0 (falsy but not nullish)', () => {
    expect(traceParams({ minDuration: 0, maxDuration: 0 })).toEqual({
      min_duration: 0,
      max_duration: 0,
    });
  });

  it('omits has_errors when hasErrors is false or undefined', () => {
    expect(traceParams({ hasErrors: false })).toEqual({});
    expect(traceParams({})).toEqual({});
  });

  it('omits environment when the array is empty', () => {
    expect(traceParams({ environment: [] })).toEqual({});
  });
});