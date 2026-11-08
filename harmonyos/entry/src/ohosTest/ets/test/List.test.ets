import parserTests from './Parser.test.ets';
import calcTests from './Calculator.test.ets';

export default function testsuite(): void {
  parserTests();
  calcTests();
}
