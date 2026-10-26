package com.faka.invisible

import com.faka.invisible.logickit.Calculator
import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {
    private fun Calculator.type(s: String) = s.forEach { if (it.isDigit()) digit(it - '0') else if (it == '.') point() else operate(it) }

    @Test fun leftToRight() { val c = Calculator(); c.type("2+3*4"); assertEquals(20.0, c.equals()!!, 0.0) }
    @Test fun decimalsAreRounded() { val c = Calculator(); c.type("0.1+0.2"); assertEquals("0.3", Calculator.format(c.equals()!!)) }
    @Test fun percentOf() { val c = Calculator(); c.type("15%80"); assertEquals(12.0, c.equals()!!, 0.0) }
    @Test fun divideByZeroIsAnError() { val c = Calculator(); c.type("5/0"); assertNull(c.equals()); assertTrue(c.error); assertEquals("Cannot divide by zero", c.spoken()) }
    @Test fun spokenState() { val c = Calculator(); c.type("12+3.5"); assertEquals("12 plus 3 point 5", c.spoken()) }
    @Test fun continueFromResult() { val c = Calculator(); c.type("6*7"); c.equals(); c.operate('+'); c.digit(8); assertEquals(50.0, c.equals()!!, 0.0) }
    @Test fun undoStepsBack() { val c = Calculator(); c.type("12+3"); c.undo(); assertEquals("12 plus", c.spoken()); c.undo(); assertEquals("12", c.spoken()); c.undo(); c.undo(); assertEquals("Zero", c.spoken()) }
    @Test fun typingAfterResultStartsNew() { val c = Calculator(); c.type("2+2"); c.equals(); c.digit(9); assertEquals("9", c.spoken()) }
    @Test fun negativeIsSpoken() { assertEquals("minus 3 point 5", Calculator.say("-3.5")) }
    @Test fun leadingZeroAndOneDot() { val c = Calculator(); c.digit(0); c.digit(0); c.digit(5); c.point(); c.point(); c.digit(2); assertEquals("5 point 2", c.spoken()) }
    @Test fun operatorChangeKeepsLeft() { val c = Calculator(); c.type("4+"); c.operate('*'); c.digit(3); assertEquals(12.0, c.equals()!!, 0.0) }
}
