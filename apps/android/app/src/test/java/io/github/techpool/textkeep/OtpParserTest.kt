package io.github.techpool.textkeep
import org.junit.Assert.*
import org.junit.Test
class OtpParserTest {
 @Test fun localRules() {
  assertEquals("482913",OtpParser.parse("482913 is your OTP. Never share it."))
  assertEquals("123456",OtpParser.parse("Your verification code is 123-456"))
  assertEquals("AB12CD",OtpParser.parse("Security code: AB12CD"))
  assertEquals("123456",OtpParser.parse("ओटीपी 123456 है"))
  assertEquals("123456",OtpParser.parse("ওটিপি 123456"))
  assertNull(OtpParser.parse("INR 1234 debited from account 5678"))
  assertNull(OtpParser.parse("OTP 1234 or 5678"))
  assertNull(OtpParser.parse("Your OTP is pending. Amount INR 1234"))
  assertNull(OtpParser.parse("Call 9876543210 for help"))
 }
}
